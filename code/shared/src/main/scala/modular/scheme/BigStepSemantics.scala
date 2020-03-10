package scalaam.modular.scheme

import scalaam.core._
import scalaam.language.scheme._
import scalaam.util.MonoidImplicits._

trait BigStepSemantics extends SchemeModFSemantics {
  // defining the intra-analysis
  override def intraAnalysis(cmp: Component) = new IntraAnalysis(cmp)
  class IntraAnalysis(cmp: Component) extends super.IntraAnalysis(cmp) with SchemeModFSemanticsIntra {
    // analysis entry point
    def analyze(): Unit = {
      componentName(cmp).foreach(n => maybePre(n, cmp))
      val result = eval(component.body)
      componentName(cmp).foreach(n => maybePost(n, result))
      writeResult(result)
    }
    // simple big-step eval
    private def eval(exp: SchemeExp): Value = exp match {
      case SchemeValue(value, _)                                  => evalLiteralValue(value)
      case lambda: SchemeLambdaExp                                => newClosure(lambda,None)
      case SchemeVarLex(_, lex)                                   => lookupVariable(lex)
      case SchemeBegin(exps, _)                                   => evalSequence(exps)
      case SchemeDefineVariable(id, vexp, _)                      => evalDefineVariable(id, vexp)
      case SchemeDefineFunction(id, prs, bdy, pos)                => evalDefineFunction(id, prs, bdy, pos)
      case SchemeDefineVarArgFunction(id, prs, vararg, bdy, pos)  => evalDefineVarArgFunction(id, prs, vararg, bdy, pos)
      case SchemeSetLex(_, lex, variable, _)                      => evalSet(lex, variable)
      case SchemeIf(prd, csq, alt, _)                             => evalIf(prd, csq, alt)
      case SchemeLet(bindings, body, _)                           => evalLetExp(bindings, body)
      case SchemeLetStar(bindings, body, _)                       => evalLetExp(bindings, body)
      case SchemeLetrec(bindings, body, _)                        => evalLetExp(bindings, body)
      case SchemeNamedLet(name,bindings,body,pos)                 => evalNamedLet(name,bindings,body,pos)
      case SchemeFuncall(fun, args, _)                            => evalCall(fun, args)
      case SchemeAnd(exps, _)                                     => evalAnd(exps)
      case SchemeOr(exps, _)                                      => evalOr(exps)
      case pair: SchemePair                                       => evalPair(pair)
      case pair: SchemeSplicedPair                                => evalSplicedPair(pair)
      case _ => throw new Exception(s"Unsupported Scheme expression: $exp")
    }
    private def evalDefineVariable(id: Identifier, exp: SchemeExp): Value = {
      val value = eval(exp)
      defineVariable(cmp, id,value)
      value
    }
    private def evalDefineFunction(id: Identifier, prs: List[Identifier], body: List[SchemeExp], idn: Identity): Value = {
      val lambda = SchemeLambda(prs,body,idn)
      val value = newClosure(lambda,Some(id.name))
      defineVariable(cmp, id,value)
      value
    }
    private def evalDefineVarArgFunction(id: Identifier, prs: List[Identifier], vararg: Identifier, body: List[SchemeExp], idn: Identity): Value = {
      val lambda = SchemeVarArgLambda(prs,vararg,body,idn)
      val value = newClosure(lambda,Some(id.name))
      defineVariable(cmp, id,value)
      value
    }
    private def evalSequence(exps: List[SchemeExp]): Value =
      exps.foldLeft(lattice.bottom)((_,exp) => eval(exp))
    private def evalSet(lex: LexicalRef, exp: SchemeExp): Value = {
      val newValue = eval(exp)
      setVariable(lex,newValue)
      newValue
    }
    private def evalIf(prd: SchemeExp, csq: SchemeExp, alt: SchemeExp): Value = conditional(eval(prd), eval(csq), eval(alt))
    private def evalLetExp(bindings: List[(Identifier,SchemeExp)], body: List[SchemeExp]): Value = {
      bindings.foreach { case (id,exp) => defineVariable(cmp, id, eval(exp)) }
      evalSequence(body)
    }
    private def evalNamedLet(id: Identifier, bindings: List[(Identifier,SchemeExp)], body: List[SchemeExp], idn: Identity): Value = {
      val (prs,ags) = bindings.unzip
      val lambda = SchemeLambda(prs,body,idn)
      val closure = newClosure(lambda,Some(id.name))
      defineVariable(cmp, id,closure)
      val argsVals = ags.map(argExp => (argExp, eval(argExp)))
      applyFun(lambda,closure,argsVals,id.idn.pos)
    }
    // R5RS specification: if all exps are 'thruty', then the value is that of the last expression
    private def evalAnd(exps: List[SchemeExp]): Value =
      if (exps.isEmpty) { lattice.bool(true) } else { evalAndLoop(exps) }
    private def evalAndLoop(exps: List[SchemeExp]): Value = (exps: @unchecked) match {
      case exp :: Nil => eval(exp)
      case exp :: rst => conditional(eval(exp),evalAndLoop(rst),lattice.bool(false))
    }
    private def evalOr(exps: List[SchemeExp]): Value = exps.foldRight(lattice.bool(false)) { (exp,acc) =>
      val vlu = eval(exp)
      conditional(vlu,vlu,acc)
    }
    private def evalCall(fun: SchemeExp, args: List[SchemeExp]): Value = {
      val funVal = eval(fun)
      val argVals = args.map(eval)
      applyFun(fun,funVal,args.zip(argVals),fun.idn.pos)
    }
    private def evalPair(pairExp: SchemePair): Value = {
      val carv = eval(pairExp.car)
      val cdrv = eval(pairExp.cdr)
      allocateCons(pairExp)(carv,cdrv)
    }
    private def evalSplicedPair(pairExp: SchemeSplicedPair): Value = {
      ???
//      val splicev = eval(pairExp.splice)
//      val cdrv = eval(pairExp.cdr)
//      append(pairExp)((pairExp.splice, splicev), (pairExp.cdr, cdrv))
    }
  }
}
