package scalaam.primitiveCompilation

import scalaam.primitiveCompilation.PrimSource._
import scalaam.primitiveCompilation.PrimTarget._
import scalaam.primitiveCompilation.ANFCompiler._
import scalaam.core._
import scalaam.language.scheme._
import scalaam.language.sexp._

object GeneratePrimitives extends App {
  import java.io._
  val file = new File("CompiledPrimitives.scala")
  val bw = new BufferedWriter(new FileWriter(file))
  for {
    name <- Definitions.names
  } {
    bw.write(PrimCompiler.compile(toANF(SchemeParser.parse(Definitions.primitiveDefinitions(name)))))
    bw.write("\n\n")
  }
  bw.close
}

object PrimCompiler {

  type SE = PrimSource.Exp
  type TE = PrimTarget.Exp

  type SA = PrimSource.AExp
  type TA = PrimTarget.AExp

  trait CompilerException extends Exception

  case class  IllegalExpressionException(e: Expression) extends Exception // For expressions that should not occur in the language compiled in the current phase.
  case class ShouldNotEncounterException(e: Expression) extends Exception // For expressions that should not be encountered by the compiler in the current phase.

  case class PrimInfo(name: String, args: List[Identifier], recursive: Boolean, storeUsage: Boolean)
  type PI = PrimInfo

  def compile(exp: SchemeExp): String = toScala(toTarget(toSource(exp)))

  /////////////////////////////
  // FIRST COMPILATION PHASE //
  /////////////////////////////

  def toSource(exp: SchemeExp): (SE, PI) = {

    def valueToSource(v: Value): SE = v match {
      case ValueBoolean(bool) => AE(PrimSource.Boo(bool))
      case ValueInteger(int) => AE(PrimSource.Num(int))
    }

    var rec: Boolean = false
    var sto: Boolean = false
    var prm: String = ""

    // Mark a primitive as recursive if a function with the same name is called within the body (note: we do not take into account bindings to the same name -> TODO).
    // TODO write this in a functional way.
    def bodyToSource(exp: Expression): SE = exp match {
      case fc@SchemeFuncall(f, args, _) =>
        val argn = args.map(arg => (bodyToSource(arg), (- arg.idn.pos._1, - arg.idn.pos._2) )) // we set primitive positions to negative values so that it does not clash with user code
        if (!argn.forall(_._1.isInstanceOf[PrimSource.AE])) throw IllegalExpressionException(fc)
        val argv: PrimSource.Args = argn.map{case (AE(ae), pos) => (ae, pos)}.toArray
        bodyToSource(f) match { // TODO maybe check arity?
          case prim@AE(PrimSource.Var(Id(name))) if prm == name =>
            rec = true
            PrimSource.PrimCall(prim, argv, true, (- fc.idn.pos._1, - fc.idn.pos._2)) // negative position
            /*
          case AE(PrimSource.Var(Id(name))) if LatticeOperations.opNams.contains(name) =>
            if (LatticeOperations.stoNams.contains(name)) sto = true // TODO is this list sufficient?
            PrimSource.OpCall(LatticeOperations.ops.find(_.name == name).get, argv, fc.idn.pos) */
          case prim => PrimSource.PrimCall(prim, argv, false, (- fc.idn.pos._1, - fc.idn.pos._2)) // negative position
        }
      case SchemeIf(cond, cons, alt, _) => bodyToSource(cond) match {
        case AE(ae) => If(ae, bodyToSource(cons), bodyToSource(alt))
        case _ => throw IllegalExpressionException(cond) // TODO: support?
      }
      // For now, let behaves as a let* TODO?
      // Restrain a body to a single expression...
      // Important: use foldRight!
      case SchemeLet(bnd, body :: Nil, _) => bnd.foldRight(bodyToSource(body)){ case ((id, bnd), acc) => Let(PrimSource.Var(Id(id.name)), bodyToSource(bnd), acc) }
      case SchemeLetStar(bnd, body :: Nil, _) => bnd.foldRight(bodyToSource(body)){ case ((id, bnd), acc) => Let(PrimSource.Var(Id(id.name)), bodyToSource(bnd), acc) }
      // Dysfunctional begin now. TODO?
      case SchemeBegin(e :: Nil, _) => bodyToSource(e)
      case SchemeDefineVariable(id, exp, _) => Let(PrimSource.Var(Id(id.name)), bodyToSource(exp), ???) // TODO
      case SchemeVar(id) => AE(PrimSource.Var(Id(id.name)))
      case SchemeValue(v, _) => valueToSource(v)
      case id@Identifier(_, _) => throw ShouldNotEncounterException(id)
      case e =>
        println(s"Illegal exp: $e")
        throw IllegalExpressionException(e)
    }

    exp match {
      // A primitive function is expected to be a function definition.
      case SchemeDefineFunction(nam, args, body :: Nil, _) =>
        prm = nam.name
        val src = bodyToSource(body)
        (src, PrimInfo(prm, args, rec, sto))
      case e => throw IllegalExpressionException(e)
    }
  }

  //////////////////////////////
  // SECOND COMPILATION PHASE //
  //////////////////////////////

  def toTarget(src: (SE, PI)): (TE, PI) = {

    val sto = src._2.storeUsage

    def AExpToTarget(ae: SA): TA = ae match {
      case PrimSource.Boo(b) => PrimTarget.Boo(b)
      case PrimSource.Num(n) => PrimTarget.Num(n)
      case PrimSource.Var(v) => PrimTarget.Var(v)
    }

    def varToTarget(v: PrimSource.Var): PrimTarget.Var = PrimTarget.Var(v.v)

    def toTarget(exp: SE): TE = exp match {
      case AE(ae) => Lat(Inj(AExpToTarget(ae)))
      case If(cond, cons, alt) => Cond(Inj(AExpToTarget(cond)), toTarget(cons), toTarget(alt))
      /*
      case If(cond, cons, alt) =>
        val v0 = PrimTarget.Var()
        val v1 = PrimTarget.Var()
        val v2 = PrimTarget.Var()
        Bind(v0, Lat(Inj(AExpToTarget(cond))), // BindC
          Bind(v1, IfTrue(Inj(v0), toTarget(cons)), // BindC
            Bind(v2, IfFalse(Inj(v0), toTarget(alt)), // BindC
              Lat(Join(Inj(v1),
                Inj(v2))))))
      */
      case Let(v, init, body) => Bind(varToTarget(v), toTarget(init), toTarget(body))
      case PrimSource.PrimCall(prim, args, rec, pos) =>
        PrimTarget.PrimCall(toTarget(prim), Args(args.map({ case (ae, pos) => (AExpToTarget(ae), pos) })), rec, sto, pos)
      //case PrimSource.OpCall(op, args, pos) =>
        //PrimTarget.LatticeOp(op, Args(args.map({ case (ae, pos) => (AExpToTarget(ae), pos) })), pos)
    }

    (toTarget(src._1), src._2)
  }

  /////////////////////////////
  // THIRD COMPILATION PHASE //
  /////////////////////////////

  def toScala(tar: (TE, PI)): String = {
    val PrimInfo(name, args, rec, sto) = tar._2
    def bodyStr: String = if (args.nonEmpty) {
      args.zipWithIndex.map({ case (a, i) =>
        s"val `${a}_pos` = args($i)._1\nval `$a` = args($i)._2"
      }).mkString("\n") ++ "\n" ++ tar._1.print(4)
    } else {
      tar._1.print(4)
    }

    def nonRecursive: String =
s"""object ${PrimTarget.scalaNameOf(name)} extends SchemePrimitive[V, A] {
  val name = "$name"
  override def call(fpos: Identity.Position, cpos: Identity.Position, args: List[(Identity.Position, V)], store: Store[A, V], alloc: SchemeAllocator[A]): MayFail[(V, Store[A, V]), Error] =
    if (args.length == ${args.length}) {
      { $bodyStr }.map(x => (x, store))
    } else MayFail.failure(PrimitiveArityError("$name", ${args.length}, args.length))
}"""
    def recursive: String =
s"""object ${PrimTarget.scalaNameOf(name)} extends SimpleFixpointPrimitive("$name", Some(${args.length})) {
  def callWithArgs(fpos: Identity.Position, cpos: Identity.Position, args: Args, store: Store[A, V], alloc: SchemeAllocator[A], $name: Args => MayFail[V, Error]): MayFail[V, Error] =
    if (args.length == ${args.length}) {
      $bodyStr
    } else MayFail.failure(PrimitiveArityError("$name", ${args.length}, args.length))
}"""
    def recursiveWithStore: String =
s"""object ${PrimTarget.scalaNameOf(name)} extends SimpleFixpointPrimitiveUsingStore("$name", Some(${args.length})) {
  def callWithArgs(fpos: Identity.Position, cpos: Identity.Position, args: Args, store: Store[A,V], recursiveCall: Args => MayFail[V, Error], alloc: SchemeAllocator[A]): MayFail[(V, Store[A, V]), Error] =
    if (args.length == ${args.length}) {
      { $bodyStr }.map(x => (x, store))
    } else MayFail.failure(PrimitiveArityError("$name", ${args.length}, args.length))
}"""
    (rec, sto) match {
      case (true, false)  => recursiveWithStore // TODO: identification of store prims is not complete: member uses store primitives but is not detected as so. For now let's just either use nonRecursive, or recursiveWithStore
      case (false, false) => nonRecursive
      case (_, true)   => recursiveWithStore
      //case (false, true)  => recursiveWithStore // TODO: are there primitives of this kind? (Note: If enabled, also enable this in PrimTarget.scala).
    }
  }

}
