package salty.tools
package compiler

import scala.tools.nsc._
import scala.tools.nsc.plugins._
import salty.ir
import salty.ir.{Expr => E, Type => Ty, Termn => Tn, Instr => I,
                 Val => V, Name => N, Stat => S, Block => B, Branch => Br}
import salty.util.ScopedVar, ScopedVar.withScopedVars

abstract class GenSaltyCode extends PluginComponent {
  import global._
  import global.definitions._

  val phaseName = "saltycode"

  override def newPhase(prev: Phase): StdPhase =
    new SaltyCodePhase(prev)

  def debug[T](msg: String)(v: T): T = { println(s"$msg = $v"); v }

  def unreachable = abort("unreachable")

  class Env {
    var used = Set.empty[ir.Name]
    var names = Map.empty[Symbol, ir.Name]

    implicit val fresh = new ir.Fresh {
      override def apply(prefix: String) = {
        var name = super.apply(prefix)
        while (used.contains(name))
          name = super.apply(prefix)
        used += name
        name
      }
    }

    def enter(sym: Symbol): ir.Name = {
      var name = N.Local(sym.name.toString)
      var i = 0
      while (used.contains(name)) {
        name = N.Local(sym.name.toString + i)
        i += 1
      }
      used += name
      names += sym -> name
      name
    }

    def resolve(sym: Symbol): ir.Name =
      names(sym)
  }

  class LabelEnv(env: Env, parent: LabelEnv) {
    var last = false
    var labels = Map.empty[Symbol, (LabelDef, ir.Block, Boolean)]

    def enterLabel(label: LabelDef): ir.Block = {
      val sym = label.symbol
      val name = env.enter(sym)
      val params = label.params.zip(sym.asMethod.paramLists.head).map {
        case (treeid, reflsym) =>
          val name = env.enter(reflsym)
          env.names += treeid.symbol -> name
          name
      }
      val instrs = params.map { param =>
        I.Assign(param, E.Phi(Seq()))
      }
      val block = B(name, instrs, Tn.Out(V.Unit))
      labels += sym -> ((label, block, last))
      block
    }

    def enterLabelCall(sym: Symbol, values: Seq[ir.Val], from: ir.Block): Unit = {
      val block = resolveLabel(sym)
      block.instrs = block.instrs.zip(values).map {
        case (I.Assign(n, E.Phi(branches)), value) =>
          I.Assign(n, E.Phi(branches :+ Br(value, from)))
        case _ =>
          unreachable
      }
    }

    def resolveLabel(sym: Symbol): ir.Block =
      labels.get(sym).map(_._2).getOrElse(parent.resolveLabel(sym))
  }

  case class CollectMutableLocalVars(var result: Set[Symbol] = Set.empty)
       extends Traverser {
    override def traverse(tree: Tree) = tree match {
      case Assign(id @ Ident(_), _) =>
        result += id.symbol
        super.traverse(tree)
      case _ =>
        super.traverse(tree)
    }

    def collect(tree: Tree) = {
      traverse(tree)
      result
    }
  }

  class SaltyCodePhase(prev: Phase) extends StdPhase(prev) {
    val currentMutableLocalVars = new ScopedVar[Set[Symbol]]
    val currentClassSym = new ScopedVar[Symbol]
    val currentMethodSym = new ScopedVar[Symbol]
    val currentEnv = new ScopedVar[Env]
    val currentLabelEnv = new ScopedVar[LabelEnv]

    implicit def fresh: ir.Fresh = currentEnv.fresh

    def noimpl = B(Tn.Out(N.Global("noimpl")))

    override def run(): Unit = {
      scalaPrimitives.init()
      super.run()
    }

    override def apply(cunit: CompilationUnit): Unit = {
      def collectClassDefs(tree: Tree): List[ClassDef] = {
        tree match {
          case EmptyTree => Nil
          case PackageDef(_, stats) => stats flatMap collectClassDefs
          case cd: ClassDef => cd :: Nil
        }
      }
      val classDefs = collectClassDefs(cunit.body)

      println("Input:")
      classDefs.foreach(println(_))

      val irClasses = classDefs.flatMap { cd =>
        val sym = cd.symbol
        if (isPrimitiveValueClass(sym) || (sym == ArrayClass)) Nil
        else List(genClass(cd))
      }

      println("\nOutput:")
      irClasses.foreach(println(_))
    }

    def genClass(cd: ClassDef): ir.Stat = withScopedVars (
      currentClassSym := cd.symbol
    ) {
      val sym = cd.symbol
      val name = encodeClassName(sym)
      val parent = encodeClassName(sym.superClass)
      val interfaces = genClassInterfaces(sym)
      val fields = genClassFields(sym)
      val methods = genClassMethods(cd.impl.body)
      val body = fields ++ methods

      if (sym.isModuleClass)
        S.Module(name, parent, interfaces, body)
      else if (sym.isInterface)
        S.Interface(name, interfaces, body)
      else
        S.Class(name, parent, interfaces, body)
    }

    def genClassInterfaces(sym: Symbol) =
      for {
        parent <- sym.info.parents
        psym = parent.typeSymbol
        if psym.isInterface
      } yield {
        encodeClassName(psym)
      }

    def genClassMethods(stats: List[Tree]): Seq[ir.Stat] =
      stats.flatMap {
        case dd: DefDef => Seq(genDef(dd))
        case _          => Seq()
      }

    def genClassFields(sym: Symbol) =
      (for {
        f <- sym.info.decls
        if !f.isMethod && f.isTerm && !f.isModule
      } yield {
        S.Var(encodeFieldName(f), genType(f.tpe))
      }).toList

    def genDef(dd: DefDef): ir.Stat = withScopedVars (
      currentMethodSym := dd.symbol
    ) {
      val sym = dd.symbol
      val name = encodeDefName(sym)
      val paramSyms = defParamSymbols(dd)
      val ty =
        if (dd.symbol.isClassConstructor) Ty.Unit
        else genType(sym.tpe.resultType)

      if (dd.symbol.isDeferred) {
        val params = genDeclParams(paramSyms)
        S.Declare(name, params, ty)
      } else {
        withScopedVars (
          currentEnv := new Env,
          currentMutableLocalVars := CollectMutableLocalVars().collect(dd.rhs)
        ) {
          val params = genDefParams(paramSyms)
          val body = genDefBody(dd.rhs)
          S.Define(name, params, ty, body)
        }
      }
    }

    def defParamSymbols(dd: DefDef): List[Symbol] = {
      val vp = dd.vparamss
      if (vp.isEmpty) Nil else vp.head.map(_.symbol)
    }

    def genDeclParams(paramSyms: List[Symbol]): Seq[ir.Type] =
      paramSyms.map(sym => genType(sym.tpe))

    def genDefParams(paramSyms: List[Symbol]): Seq[ir.LabeledType] =
      paramSyms.map { sym =>
        val name = currentEnv.enter(sym)
        val ty = genType(sym.tpe)
        ir.LabeledType(name, ty)
      }

    def genDefBody(body: Tree): ir.Block = genExpr(body).simplify

    def genExpr(tree: Tree): ir.Block = tree match {
      case label: LabelDef =>
        genLabel(label)

      case vd: ValDef =>
        genExpr(vd.rhs).merge { (pre, v) =>
          val isMutable = currentMutableLocalVars.contains(vd.symbol)
          val name = currentEnv.enter(vd.symbol)
          if (!isMutable)
            B(pre :+
              I.Assign(name, v),
              Tn.Out(V.Unit))
          else
            B(pre :+
              I.Assign(name, E.Alloc(genType(vd.symbol.tpe))) :+
              E.Store(name, v),
              Tn.Out(V.Unit))
        }

      case If(cond, thenp, elsep) =>
        genExpr(cond).merge { (pre, v) =>
          B(pre, Tn.If(v, genExpr(thenp), genExpr(elsep)))
        }

      case Return(expr) =>
        genExpr(expr).merge { (pre, v) =>
          B(pre, Tn.Return(v))
        }

      case t: Try =>
        noimpl

      case Throw(expr) =>
        genExpr(expr).merge { (pre, v) =>
          B(pre, Tn.Throw(v))
        }

      case app: Apply =>
        genApply(app)

      case app: ApplyDynamic =>
        genApplyDynamic(app)

      case This(qual) =>
        B(Tn.Out(
          if (tree.symbol == currentClassSym.get) V.This
          else encodeClassName(tree.symbol)))

      case Select(qual, sel) =>
        val sym = tree.symbol
        if (sym.isModule)
          B(Tn.Out(encodeClassName(sym)))
        else
          genExpr(qual).merge { (pre, v) =>
            val n = fresh()
            B(pre :+
              I.Assign(n, E.Load(V.Elem(v, encodeFullFieldName(tree.symbol)))),
              Tn.Out(n))
          }

      case id: Ident =>
        val sym = id.symbol
        if (!currentMutableLocalVars.contains(sym))
          B(Tn.Out(
            if (sym.isModule) encodeClassName(sym)
            else currentEnv.resolve(sym)))
        else {
          val name = fresh()
          B(Seq(I.Assign(name, E.Load(currentEnv.resolve(sym)))),
            Tn.Out(name))
        }

      case lit: Literal =>
        B(Tn.Out(genValue(lit)))

      case block: Block =>
        genBlock(block)

      case Typed(Super(_, _), _) =>
        B(Tn.Out(V.This))

      case Typed(expr, _) =>
        genExpr(expr)

      case Assign(lhs, rhs) =>
        lhs match {
          case sel @ Select(qual, _) =>
            genExpr(qual).chain(genExpr(rhs)) { (pre, vqual, vrhs) =>
              B(pre :+
                E.Store(V.Elem(vqual, encodeFullFieldName(sel.symbol)), vrhs),
                Tn.Out(V.Unit))
            }
          case id: Ident =>
            genExpr(rhs).merge { (pre, v) =>
              val store = E.Store(currentEnv.resolve(id.symbol), v)
              B(pre :+ store, Tn.Out(V.Unit))
            }
        }

      case av: ArrayValue =>
        genArrayValue(av)

      case m: Match =>
        genSwitch(m)

      case fun: Function =>
        noimpl

      case EmptyTree =>
        B(Tn.Out(V.Unit))

      case _ =>
        abort("Unexpected tree in genExpr: " +
              tree + "/" + tree.getClass + " at: " + tree.pos)
    }

    def genBlock(block: Block) = withScopedVars (
      currentLabelEnv := new LabelEnv(currentEnv, currentLabelEnv)
    ) {
      val Block(stats, last) = block
      val res = B.chain(stats.map(genExpr)) { (instrs, vals) =>
        currentLabelEnv.last = true
        genExpr(last)
      }
      genLabelBodies()
      res
    }

    def genLabel(label: LabelDef) =
      currentLabelEnv.enterLabel(label)

    def genLabelBodies(): Unit = {
      for ((_, (label, block, last)) <- currentLabelEnv.labels) {
        val termn = block.termn
        block.termn = Tn.Return(V.Unit)
        val rhsblock = genExpr(label.rhs)
        val target =
          if (last) rhsblock
          else rhsblock.merge { (pre, v) => B(termn) }
        block.termn = Tn.Jump(target)
      }
    }

    def genValue(lit: Literal): ir.Val = {
      val value = lit.value
      value.tag match {
        case NullTag =>
          V.Null
        case UnitTag =>
          V.Unit
        case BooleanTag =>
          V.Bool(value.booleanValue)
        case ByteTag =>
          V.Number(value.intValue.toString, Ty.I8)
        case ShortTag | CharTag =>
          V.Number(value.intValue.toString, Ty.I16)
        case IntTag =>
          V.Number(value.intValue.toString, Ty.I32)
        case LongTag =>
          V.Number(value.longValue.toString, Ty.I64)
        case FloatTag =>
          V.Number(value.floatValue.toString, Ty.F32)
        case DoubleTag =>
          V.Number(value.doubleValue.toString, Ty.F64)
        case StringTag =>
          ???
        case ClazzTag =>
          ???
        case EnumTag =>
          ???
      }
    }

    // TODO: insert coercions
    def genArrayValue(av: ArrayValue) = {
      val ArrayValue(tpt, elems) = av
      B.chain(elems.map(genExpr)) { (pre, values) =>
        val ty = genType(tpt.tpe)
        val len = values.length
        val n = fresh()
        B(pre :+
          I.Assign(n, E.Alloc(Ty.Array(ty, len))) :+
          E.Store(n, V.Array(values)),
          Tn.Out(V.Slice(n, V(len))))
      }
    }

    def genSwitch(m: Match) = {
      val Match(sel, cases) = m

      genExpr(sel).merge { (pre, selvalue) =>
        val defaultBody =
          cases.collectFirst {
            case c @ CaseDef(Ident(nme.WILDCARD), _, body) => body
          }.get
        val defaultBlock = genExpr(defaultBody)
        val branches = cases.flatMap { case CaseDef(pat, guard, body) =>
          val bodyBlock = genExpr(body)
          val guardedBlock =
            if (guard.isEmpty) bodyBlock
            else
              genExpr(guard).merge { (pre, gv) =>
                B(pre, Tn.If(gv, bodyBlock, defaultBlock))
              }
          val values =
            pat match {
              case lit: Literal =>
                Seq(genValue(lit))
              case Alternative(alts) =>
                alts.map { case lit: Literal => genValue(lit) }
              case _ =>
                Seq()
            }
          values.map(Br(_, guardedBlock))
        }

        B(pre, Tn.Switch(selvalue, defaultBlock, branches))
      }
    }

    def genApplyDynamic(app: ApplyDynamic) = noimpl

    def genApply(app: Apply): ir.Block = {
      val Apply(fun, args) = app

      fun match {
        case _: TypeApply =>
          genApplyTypeApply(app)
        case Select(Super(_, _), _) =>
          genApplySuper(app)
        case Select(New(_), nme.CONSTRUCTOR) =>
          genApplyNew(app)
        case _ =>
          val sym = fun.symbol

          if (sym.isLabel) {
            genLabelApply(app)
          } else if (scalaPrimitives.isPrimitive(sym)) {
            genPrimitiveOp(app)
          } else if (currentRun.runDefinitions.isBox(sym)) {
            val arg = args.head
            makePrimitiveBox(arg, arg.tpe)
          } else if (currentRun.runDefinitions.isUnbox(sym)) {
            makePrimitiveUnbox(args.head, app.tpe)
          } else {
            genNormalApply(app)
          }
      }
    }

    def genLabelApply(tree: Tree) = tree match {
      case Apply(fun, Nil) =>
        currentLabelEnv.resolveLabel(fun.symbol)
      case Apply(fun, args) =>
        B.chain(args.map(genExpr)) { (instrs, vals) =>
          val block = B(instrs, Tn.Jump(currentLabelEnv.resolveLabel(fun.symbol)))
          currentLabelEnv.enterLabelCall(fun.symbol, vals, block)
          block
        }
    }

    lazy val primitive2box = Map(
      ByteTpe   -> N.Global("java.lang.Byte"),
      CharTpe   -> N.Global("java.lang.Character"),
      ShortTpe  -> N.Global("java.lang.Short"),
      IntTpe    -> N.Global("java.lang.Integer"),
      LongTpe   -> N.Global("java.lang.Long"),
      FloatTpe  -> N.Global("java.lang.Float"),
      DoubleTpe -> N.Global("java.lang.Double")
    )

    lazy val ctorName = N.Global(nme.CONSTRUCTOR.toString)

    def makePrimitiveBox(expr: Tree, tpe: Type) =
      genExpr(expr).merge { (pre, v) =>
        val name = fresh()
        B(pre :+ I.Assign(name, E.Box(v, primitive2box(tpe))), Tn.Out(name))
      }

    def makePrimitiveUnbox(expr: Tree, tpe: Type) =
      genExpr(expr).merge { (pre, v) =>
        val name = fresh()
        B(pre :+ I.Assign(name, E.Unbox(v, primitive2box(tpe))), Tn.Out(name))
      }

    def genPrimitiveOp(app: Apply): ir.Block = {
      import scalaPrimitives._

      val sym = app.symbol
      val Apply(fun @ Select(receiver, _), args) = app
      val code = scalaPrimitives.getPrimitive(sym, receiver.tpe)

      if (isArithmeticOp(code) || isLogicalOp(code) || isComparisonOp(code))
        genSimpleOp(app, receiver :: args, code)
      else if (code == scalaPrimitives.CONCAT)
        genStringConcat(app, receiver, args)
      else if (code == HASH)
        genScalaHash(app, receiver)
      else if (isArrayOp(code))
        genArrayOp(app, code)
      else if (code == SYNCHRONIZED)
        genSynchronized(app)
      else if (isCoercion(code))
        genCoercion(app, receiver, code)
      else
        abort("Unknown primitive operation: " + sym.fullName + "(" +
              fun.symbol.simpleName + ") " + " at: " + (app.pos))
    }

    def genSimpleOp(app: Apply, args: List[Tree], code: Int): ir.Block = {
      import scalaPrimitives._

      val resType = genType(app.tpe)

      args match {
        case List(unary) =>
          val unaryb = genExpr(unary)
          if (code == POS)
            unaryb
          else
            unaryb.merge { (pre, value) =>
              val expr = code match {
                case NEG  => E.Bin(E.Bin.Sub, V.Number("0", resType), value)
                case NOT  => E.Bin(E.Bin.Xor, V.Number("-1", resType), value)
                case ZNOT => E.Bin(E.Bin.Xor, V.Bool(true), value)
                case _ =>
                  abort("Unknown unary operation code: " + code)
              }
              val res = fresh()
              B(pre :+ I.Assign(res, expr), Tn.Out(res))
            }

        // TODO: convert to the common type
        // TODO: eq, ne, ||, &&
        case List(left, right) =>
          val lblock = genExpr(left)
          val rblock = genExpr(right)
          lblock.chain(rblock) { (instrs, lvalue, rvalue) =>
            val expr = code match {
              case ADD  => E.Bin(E.Bin.Add,  lvalue, rvalue)
              case SUB  => E.Bin(E.Bin.Sub,  lvalue, rvalue)
              case MUL  => E.Bin(E.Bin.Mul,  lvalue, rvalue)
              case DIV  => E.Bin(E.Bin.Div,  lvalue, rvalue)
              case MOD  => E.Bin(E.Bin.Mod,  lvalue, rvalue)
              case OR   => E.Bin(E.Bin.Or,   lvalue, rvalue)
              case XOR  => E.Bin(E.Bin.Xor,  lvalue, rvalue)
              case AND  => E.Bin(E.Bin.And,  lvalue, rvalue)
              case LSL  => E.Bin(E.Bin.Shl,  lvalue, rvalue)
              case LSR  => E.Bin(E.Bin.Lshr, lvalue, rvalue)
              case ASR  => E.Bin(E.Bin.Ashr, lvalue, rvalue)
              case EQ   => E.Bin(E.Bin.Eq,   lvalue, rvalue)
              case NE   => E.Bin(E.Bin.Neq,  lvalue, rvalue)
              case LT   => E.Bin(E.Bin.Lt,   lvalue, rvalue)
              case LE   => E.Bin(E.Bin.Lte,  lvalue, rvalue)
              case GT   => E.Bin(E.Bin.Gt,   lvalue, rvalue)
              case GE   => E.Bin(E.Bin.Gte,  lvalue, rvalue)
              case ID   => ???
              case NI   => ???
              case ZOR  => ??? // If(lvalue, V.Bool(true), rblock)
              case ZAND => ??? // If(lvalue, rblock, V.Bool(false))
              case _ =>
                abort("Unknown binary operation code: " + code)
            }
            val res = fresh()
            B(instrs :+ I.Assign(res, expr), Tn.Out(res))
          }

        case _ =>
          abort("Too many arguments for primitive function: " + app)
      }
    }

    def genStringConcat(app: Apply, receiver: Tree, args: List[Tree]) = noimpl

    def genScalaHash(app: Apply, receiver: Tree) = noimpl

    def genArrayOp(app: Apply, code: Int) = {
      import scalaPrimitives._

      val Apply(Select(array, _), args) = app
      val blocks = (array +: args).map(genExpr)
      B.chain(blocks) { (pre, values) =>
        val arrayvalue +: argvalues = values

        if (scalaPrimitives.isArrayGet(code)) {
          val n = fresh()
          B(pre :+
            I.Assign(n, E.Load(V.Elem(arrayvalue, argvalues(0)))),
            Tn.Out(n))
        } else if (scalaPrimitives.isArraySet(code)) {
          val n = fresh()
          B(pre :+
            E.Store(V.Elem(arrayvalue, argvalues(0)), argvalues(1)),
            Tn.Out(V.Unit))
        } else {
          val n = fresh()
          B(pre :+
            I.Assign(n, E.Length(arrayvalue)),
            Tn.Out(n))
        }
      }
    }

    def genSynchronized(app: Apply) = noimpl

    def genCoercion(app: Apply, receiver: Tree, code: Int) = {
      val block = genExpr(receiver)
      val (fromty, toty) = coercionTypes(code)

      if (fromty == toty) block
      else block.merge { (pre, value) =>
        val expr = (fromty, toty) match {
          case (Ty.I(lwidth), Ty.I(rwidth)) if lwidth < rwidth =>
            E.Conv(E.Conv.Zext, value, toty)
          case (Ty.I(lwidth), Ty.I(rwidth)) if lwidth > rwidth =>
            E.Conv(E.Conv.Trunc, value, toty)
          case (Ty.I(_), Ty.F(_)) =>
            E.Conv(E.Conv.Sitofp, value, toty)
          case (Ty.F(_), Ty.I(_)) =>
            E.Conv(E.Conv.Fptosi, value, toty)
          case (Ty.F64, Ty.F32) =>
            E.Conv(E.Conv.Fptrunc, value, toty)
          case (Ty.F32, Ty.F64) =>
            E.Conv(E.Conv.Fpext, value, toty)
        }
        val res = fresh()
        B(pre :+ I.Assign(res, expr), Tn.Out(res))
      }
    }

    def coercionTypes(code: Int): (ir.Type, ir.Type) = {
      import scalaPrimitives._

      code match {
        case B2B       => (Ty.I8, Ty.I8)
        case B2S | B2C => (Ty.I8, Ty.I16)
        case B2I       => (Ty.I8, Ty.I32)
        case B2L       => (Ty.I8, Ty.I64)
        case B2F       => (Ty.I8, Ty.F32)
        case B2D       => (Ty.I8, Ty.F64)

        case S2B       | C2B       => (Ty.I16, Ty.I8)
        case S2S | S2C | C2S | C2C => (Ty.I16, Ty.I16)
        case S2I       | C2I       => (Ty.I16, Ty.I32)
        case S2L       | C2L       => (Ty.I16, Ty.I64)
        case S2F       | C2F       => (Ty.I16, Ty.F32)
        case S2D       | C2D       => (Ty.I16, Ty.F64)

        case I2B       => (Ty.I32, Ty.I8)
        case I2S | I2C => (Ty.I32, Ty.I16)
        case I2I       => (Ty.I32, Ty.I32)
        case I2L       => (Ty.I32, Ty.I64)
        case I2F       => (Ty.I32, Ty.F32)
        case I2D       => (Ty.I32, Ty.F64)

        case L2B       => (Ty.I64, Ty.I8)
        case L2S | L2C => (Ty.I64, Ty.I16)
        case L2I       => (Ty.I64, Ty.I32)
        case L2L       => (Ty.I64, Ty.I64)
        case L2F       => (Ty.I64, Ty.F32)
        case L2D       => (Ty.I64, Ty.F64)

        case F2B       => (Ty.F32, Ty.I8)
        case F2S | F2C => (Ty.F32, Ty.I16)
        case F2I       => (Ty.F32, Ty.I32)
        case F2L       => (Ty.F32, Ty.I64)
        case F2F       => (Ty.F32, Ty.F32)
        case F2D       => (Ty.F32, Ty.F64)

        case D2B       => (Ty.F64, Ty.I8)
        case D2S | D2C => (Ty.F64, Ty.I16)
        case D2I       => (Ty.F64, Ty.I32)
        case D2L       => (Ty.F64, Ty.I64)
        case D2F       => (Ty.F64, Ty.F32)
        case D2D       => (Ty.F64, Ty.F64)
      }
    }

    def genApplyTypeApply(app: Apply) = {
      val Apply(TypeApply(fun @ Select(obj, _), targs), _) = app
      val ty = genType(targs.head.tpe)

      genExpr(obj).merge { (pre, v) =>
        val expr = fun.symbol match {
          case Object_isInstanceOf => E.Is(v, ty)
          case Object_asInstanceOf => E.Conv(E.Conv.Dyncast, v, ty)
        }
        val res = fresh()
        val instr = I.Assign(res, expr)
        B(pre :+ instr, Tn.Out(res))
      }
    }

    def genApplySuper(app: Apply) = noimpl

    def genApplyNew(app: Apply) = {
      val Apply(fun @ Select(New(tpt), nme.CONSTRUCTOR), args) = app
      val ctor = fun.symbol
      val tpe = tpt.tpe

      genType(tpe) match {
        case ty @ Ty.Slice(_) =>
          genNewArray(ty, args.head)
        case classty: N.Global =>
          genNew(classty, ctor, args)
        case _ =>
          abort("unexpected new: " + app)
      }
    }

    def genNewArray(ty: ir.Type.Slice, length: Tree) = {
      val Ty.Slice(elemty) = ty
      val n = fresh()

      genExpr(length).merge { (pre, v) =>
        B(pre :+
          I.Assign(n, E.Alloc(elemty, v)),
          Tn.Out(V.Slice(n, v)))
      }
    }

    def genNew(ty: ir.Name.Global, ctorsym: Symbol, args: List[Tree]) =
      B.chain(args.map(genExpr)) { (instrs, values) =>
        val ctor = N.Nested(ty, encodeDefName(ctorsym))
        val res = fresh()
        B(instrs :+
          I.Assign(res, E.Alloc(ty)) :+
          E.Call(ctor, res +: values),
          Tn.Out(res))
      }

    def genNormalApply(app: Apply) = {
      val Apply(fun @ Select(receiver, _), args) = app
      val blocks = (receiver +: args).map(genExpr)
      B.chain(blocks) { (instrs, values) =>
        val res = fresh()
        val mname = N.Nested(encodeClassName(receiver.symbol),
                             encodeDefName(fun.symbol))
        val callinstr = I.Assign(res, E.Call(mname, values))
        B(instrs :+ callinstr, Tn.Out(res))
      }
    }

    lazy val genObjectType = Ty.Ptr(N.Global("java.lang.Object"))

    def genRefType(sym: Symbol, targs: List[Type] = Nil) = sym match {
      case ArrayClass   => Ty.Slice(genType(targs.head))
      case NullClass    => Ty.Null
      case NothingClass => Ty.Nothing
      case _            => encodeClassName(sym)
    }

    lazy val genPrimitiveType: PartialFunction[Symbol, ir.Type] = {
      case UnitClass    => Ty.Unit
      case BooleanClass => Ty.Bool
      case ByteClass    => Ty.I8
      case CharClass    => Ty.I16
      case ShortClass   => Ty.I16
      case IntClass     => Ty.I32
      case LongClass    => Ty.I64
      case FloatClass   => Ty.F32
      case DoubleClass  => Ty.F64
    }

    def genPrimitiveOrRefType(sym: Symbol, targs: List[Type] = Nil) =
      genPrimitiveType.applyOrElse(sym, genRefType((_: Symbol), targs))

    def genType(t: Type): ir.Type = t.normalize match {
      case ThisType(ArrayClass)            => genObjectType
      case ThisType(sym)                   => genRefType(sym)
      case SingleType(_, sym)              => genPrimitiveOrRefType(sym)
      case ConstantType(_)                 => genType(t.underlying)
      case TypeRef(_, sym, args)           => genPrimitiveOrRefType(sym, args)
      case ClassInfoType(_, _, ArrayClass) => abort("ClassInfoType to ArrayClass!")
      case ClassInfoType(_, _, sym)        => genPrimitiveOrRefType(sym)
      case t: AnnotatedType                => genType(t.underlying)
      case tpe: ErasedValueType            => genRefType(tpe.valueClazz)
    }


    def encodeFullFieldName(sym: Symbol) = N.Nested(encodeClassName(sym.owner),
                                                    encodeFieldName(sym))

    def encodeFieldName(sym: Symbol) = N.Global(sym.name.toString)

    def encodeDefName(sym: Symbol) = N.Global(sym.name.toString)

    def encodeClassName(sym: Symbol) = N.Global(sym.fullName.toString)
  }
}