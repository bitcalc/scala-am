package scalaam.modular.scheme

import scalaam.modular.components.ContextSensitiveComponents
import scalaam.language.scheme.primitives._
import scalaam.core.Position._
import scalaam.core._
import scalaam.modular._
import scalaam.language.scheme._
import scalaam.language.sexp
import scalaam.util._

/**
 * Base definitions for a Scheme MODF analysis.
 */
// TODO: Most of this can be factored out to SchemeSemantics
trait BaseSchemeModFSemantics extends SchemeSemantics
                                 with ReturnValue[SchemeExp] 
                                 with ContextSensitiveComponents[SchemeExp] {

  //XXXXXXXXXXXXXXXXXXXXXXXXX//
  // COMPONENTS AND CONTEXTS //
  //XXXXXXXXXXXXXXXXXXXXXXXXX//

  // In ModF, components are function calls in some context.
  // All components used together with this Scheme MODF analysis should be viewable as SchemeComponents.

  def view(cmp: Component): SchemeModFComponent[ComponentContext,Addr]

  def body(cmp: Component): SchemeExp = body(view(cmp))
  def body(cmp: SchemeModFComponent[ComponentContext,Addr]): SchemeExp = cmp match {
    case Main                           => program 
    case c: Call[ComponentContext,Addr] => SchemeBody(c.lambda.body)
  }

  type ComponentContent = Option[lattice.Closure]
  def content(cmp: Component) = view(cmp) match {
    case Main                           => None
    case c: Call[ComponentContext,Addr] => Some(c.clo)
  }
  def context(cmp: Component): Option[ComponentContext] = view(cmp) match {
    case Main                           => None
    case c: Call[ComponentContext,Addr] => Some(c.ctx)
  }

  /** Creates a new component, given a closure, context and an optional name. */
  def newComponent(call: Call[ComponentContext,Addr]): Component

  /** Creates a new context given a closure, a list of argument values and the position of the call site. */
  def allocCtx(nam: Option[String], clo: lattice.Closure, args: List[Value], call: Position, caller: Component): ComponentContext

  //XXXXXXXXXXXXXXXXXXXXXXXXXX//
  // INTRA-COMPONENT ANALYSIS //
  //XXXXXXXXXXXXXXXXXXXXXXXXXX//

  // Extensions to the intraAnalysis.
  trait SchemeModFSemanticsIntra extends super.IntraAnalysis with GlobalStoreIntra with ReturnResultIntra {
    // components
    protected def fnBody: SchemeExp = body(view(component))
    protected def fnEnv: Env = view(component) match {
      case Main                           => initialEnv
      case c: Call[ComponentContext,Addr] => c.env.extend(c.lambda.args.map { id =>
        (id.name, allocAddr(VarAddr(id)))
      })
    }
    // variable lookup: use the global store
    protected def lookup(id: Identifier, env: Env): Value = env.lookup(id.name) match {
      case None       => throw new Exception(s"Undefined variable $id") //TODO: better error reporting
      case Some(addr) => readAddr(addr)
    }
    protected def assign(id: Identifier, env: Env, vlu: Value): Unit = env.lookup(id.name) match {
      case None       => throw new Exception(s"Undefined variable $id") //TODO: better error reporting
      case Some(addr) => writeAddr(addr, vlu)   
    }
    protected def assign(bds: List[(Identifier,Value)], env: Env): Unit = 
      bds.foreach { case (id,vlu) => assign(id,env,vlu) }
    protected def bind(id: Identifier, env: Env, vlu: Value): Env = {
      val addr = allocAddr(VarAddr(id))
      val env2 = env.extend(id.name,addr)
      writeAddr(addr,vlu)
      env2
    }
    protected def bind(bds: List[(Identifier,Value)], env: Env): Env =
      bds.foldLeft(env)((env2, bnd) => bind(bnd._1, env2, bnd._2))
    protected def applyFun(fexp: SchemeFuncall, fval: Value, args: List[(SchemeExp,Value)], cll: Position): Value =
      if(args.forall(_._2 != lattice.bottom)) {
        val fromClosures = applyClosures(fval,args,cll)
        val fromPrimitives = applyPrimitives(fexp,fval,args)
        lattice.join(fromClosures,fromPrimitives)
      } else {
        lattice.bottom
      }
    // TODO[minor]: use foldMap instead of foldLeft
    protected def applyClosures(fun: Value, args: List[(SchemeExp,Value)], cll: Position): Value = {
      val arity = args.length
      val closures = lattice.getClosures(fun)
      closures.foldLeft(lattice.bottom)((acc,clo) => lattice.join(acc, clo match {
        case (clo@(SchemeLambda(prs,_,_),_), nam) if prs.length == arity =>
          val argVals = args.map(_._2)
          val context = allocCtx(nam, clo, argVals, cll, component)
          val targetCall = Call(clo,nam,context)
          val targetCmp = newComponent(targetCall)
          bindArgs(targetCmp, prs, argVals)
          call(targetCmp)
        case (clo@(SchemeVarArgLambda(prs,vararg,_,_),_), nam) if prs.length <= arity =>
          val (fixedArgs,varArgs) = args.splitAt(prs.length)
          val fixedArgVals = fixedArgs.map(_._2)
          val varArgVal = allocateList(varArgs)
          val context = allocCtx(nam, clo, fixedArgVals :+ varArgVal, cll, component)
          val targetCall = Call(clo,nam,context)
          val targetCmp = newComponent(targetCall)
          bindArgs(targetCmp,prs,fixedArgVals)
          bindArg(targetCmp,vararg,varArgVal)
          call(targetCmp)
        case _ => lattice.bottom
      }))
    }
    protected def allocateList(elms: List[(SchemeExp,Value)]): Value = elms match {
      case Nil                => lattice.nil
      case (exp,vlu) :: rest  => allocateCons(exp)(vlu,allocateList(rest))
    }
    protected def allocateCons(pairExp: SchemeExp)(car: Value, cdr: Value): Value = {
      val addr = allocAddr(PtrAddr(pairExp))
      val pair = lattice.cons(car,cdr)
      writeAddr(addr,pair)
      lattice.pointer(addr)
    }
    protected def append(appendExp: SchemeExp)(l1: (SchemeExp, Value), l2: (SchemeExp, Value)): Value = {
      //TODO [difficult]: implement append
      throw new Exception("NYI -- append")
    }
    private def bindArg(component: Component, par: Identifier, arg: Value): Unit =
      writeAddr(componentAddr(component,VarAddr(par)), arg)
    private def bindArgs(component: Component, pars: List[Identifier], args: List[Value]): Unit =
      pars.zip(args).foreach { case (par,arg) => bindArg(component,par,arg) }

    private val allocator: SchemeAllocator[Addr] = new SchemeAllocator[Addr] {
      def pointer(exp: SchemeExp): Addr = allocAddr(PtrAddr(exp))
    }
    // TODO[minor]: use foldMap instead of foldLeft
    protected def applyPrimitives(fexp: SchemeFuncall, fval: Value, args: List[(SchemeExp,Value)]): Value =
      lattice.getPrimitives(fval).foldLeft(lattice.bottom)((acc,prm) => lattice.join(acc,
        prm.call(fexp, args, StoreAdapter, allocator) match {
          case MayFailSuccess((vlu,_))  => vlu
          case MayFailBoth((vlu,_),_)   => vlu
          case MayFailError(_)          => lattice.bottom
        }
      ))
    // primitives glue code
    // TODO[maybe]: while this should be sound, it might be more precise to not immediately write every value update to the global store ...
    case object StoreAdapter extends Store[Addr,Value] {
      def lookup(a: Addr): Option[Value] = Some(readAddr(a))
      def extend(a: Addr, v: Value): Store[Addr, Value] = { writeAddr(a,v) ; this }
    }
    // evaluation helpers
    protected def evalLiteralValue(literal: sexp.Value): Value = literal match {
      case sexp.ValueInteger(n)   => lattice.number(n)
      case sexp.ValueReal(r)      => lattice.real(r)
      case sexp.ValueBoolean(b)   => lattice.bool(b)
      case sexp.ValueString(s)    => lattice.string(s)
      case sexp.ValueCharacter(c) => lattice.char(c)
      case sexp.ValueSymbol(s)    => lattice.symbol(s)
      case sexp.ValueNil          => lattice.nil
      case _ => throw new Exception(s"Unsupported Scheme literal: $literal")
    }
    // The current component serves as the lexical environment of the closure.
    protected def newClosure(lambda: SchemeLambdaExp, env: Env, name: Option[String]): Value =
      lattice.closure((lambda, env.restrictTo(lambda.fv)), name)

    // other helpers
    protected def conditional[M : Monoid](prd: Value, csq: => M, alt: => M): M = {
      val csqVal = if (lattice.isTrue(prd)) csq else Monoid[M].zero
      val altVal = if (lattice.isFalse(prd)) alt else Monoid[M].zero
      Monoid[M].append(csqVal,altVal)
    }
  }
}

trait SchemeModFSemantics extends BaseSchemeModFSemantics
                             with DedicatedSchemeSemantics