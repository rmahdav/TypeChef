package de.fosd.typechef.typesystem

import _root_.de.fosd.typechef.conditional._
import _root_.de.fosd.typechef.featureexpr.{FeatureExprFactory, FeatureExpr}
import _root_.de.fosd.typechef.parser.c.{AST, Declarator}
import FeatureExprFactory._

/**
 * bundles all environments during type checking
 */
trait CEnv {

    object EmptyEnv extends Env(new ConditionalTypeMap(), new VarTypingContext(), new StructEnv(), Map(), Map(), None, 0, False)

    protected class Env(
                           val typedefEnv: ConditionalTypeMap,
                           val varEnv: VarTypingContext,
                           val structEnv: StructEnv,
                           val enumEnv: EnumEnv,
                           val labelEnv: LabelEnv,
                           val expectedReturnType: Option[Conditional[CType]], //for a function
                           val scope: Int,
                           val isDeadCode: FeatureExpr
                           ) {

        private def copy(typedefEnv: ConditionalTypeMap = this.typedefEnv, varEnv: VarTypingContext = this.varEnv, structEnv: StructEnv = this.structEnv, enumEnv: EnumEnv = this.enumEnv, labelEnv: LabelEnv = this.labelEnv, expectedReturnType: Option[Conditional[CType]] = this.expectedReturnType, scope: Int = this.scope, isDeadCode: FeatureExpr = this.isDeadCode) = new Env(typedefEnv, varEnv, structEnv, enumEnv, labelEnv, expectedReturnType, scope, isDeadCode)

        //varenv
        def updateVarEnv(newVarEnv: VarTypingContext) = if (newVarEnv == varEnv) this else new Env(typedefEnv, newVarEnv, structEnv, enumEnv, labelEnv, expectedReturnType, scope, isDeadCode)
        def addVar(name: String, f: FeatureExpr, d: AST, t: Conditional[CType], kind: DeclarationKind, scope: Int) = updateVarEnv(varEnv +(name, f, d, t, kind, scope))
        def addVars(vars: Seq[(String, FeatureExpr, AST, Conditional[CType], DeclarationKind)], scope: Int) =
            updateVarEnv(vars.foldLeft(varEnv)((ve, v) => ve.+(v._1, v._2, v._3, v._4, v._5, scope)))
        def addVars(vars: Seq[(String, FeatureExpr, AST, Conditional[CType])], kind: DeclarationKind, scope: Int) =
            updateVarEnv(vars.foldLeft(varEnv)((ve, v) => ve.+(v._1, v._2, v._3, v._4, kind, scope)))

        //structenv
        def updateStructEnv(s: StructEnv) = if (s == structEnv) this else copy(structEnv = s)
        //enumenv
        def updateEnumEnv(s: EnumEnv) = if (s == enumEnv) this else copy(enumEnv = s)

        //enumenv
        def updateLabelEnv(s: LabelEnv) = if (s == labelEnv) this else copy(labelEnv = s)

        //typedefenv
        private def updateTypedefEnv(newTypedefEnv: ConditionalTypeMap) = if (newTypedefEnv == typedefEnv) this else new Env(newTypedefEnv, varEnv, structEnv, enumEnv, labelEnv, expectedReturnType, scope, isDeadCode)
        def addTypedefs(typedefs: ConditionalTypeMap) = updateTypedefEnv(typedefEnv ++ typedefs)
        def addTypedefs(typedefs: Seq[(String, FeatureExpr, (AST, Conditional[CType]))]) = updateTypedefEnv(typedefEnv ++ typedefs)
        def addTypedef(name: String, f: FeatureExpr, d: AST, t: Conditional[CType]) = updateTypedefEnv(typedefEnv +(name, f, d, t))

        //expectedReturnType
        def setExpectedReturnType(newExpectedReturnType: Conditional[CType]) = this.copy(expectedReturnType = Some(newExpectedReturnType))

        def incScope() = new Env(typedefEnv, varEnv, structEnv, enumEnv, labelEnv, expectedReturnType, scope + 1, isDeadCode)

        def markDead(condition: FeatureExpr) = this.copy(isDeadCode = this.isDeadCode or condition)
    }


    /*****
     * Variable-Typing context (collects all top-level and local declarations)
     * variables with local scope overwrite variables with global scope
     */
    //Variable-Typing Context: identifier to its non-void wellformed type
    type VarTypingContext = ConditionalVarEnv

    //    possible changes:
    //      case e: Declaration => outerVarEnv(e) ++ declType(e)
    //        case fun: FunctionDef => outerVarEnv(fun) + (fun.getName, fun -> featureExpr, ctype(fun))
    //        case e@DeclarationStatement(decl) => outerVarEnv(e) ++ declType(decl)
    //        //parameters in the body of functions
    //        case c@CompoundStatement(_) => c -> parentAST match {                     TODO
    //            case FunctionDef(_, decl, _, _) => outerVarEnv(c) ++ parameterTypes(decl)
    //            case NestedFunctionDef(_, _, decl, _, _) => outerVarEnv(c) ++ parameterTypes(decl)
    //            case _ => outerVarEnv(c)
    //        }
    //        TODO case nfun: NestedFunctionDef => outerVarEnv(nfun) + (nfun.getName, nfun -> featureExpr, ctype(nfun))


    /**
     * for struct and union
     * ConditionalTypeMap represents for the fields of the struct
     *
     * structs do not need to be defined, but they can be complete (with fields) or incomplete
     *
     * we store whether a structure with this name is complete (FeatureExpr).
     * a redeclaration in an inner scope may reduce completeness again
     *
     * we do not distinguish between alternative structures. fields are merged in
     * one ConditionalTypeMap entry, but by construction they cannot overlap if
     * the structure declarations do not overlap variant-wise
     *
     * the structEnv maps a tag name to a conditional tuple (isComplete, fields, scope)
     */
    case class StructTag(isComplete: Boolean, fields: ConditionalTypeMap, scope: Int)

    class StructEnv(private val env: Map[(String, Boolean), Conditional[StructTag]]) {
        def this() = this(Map())
        private val emptyFields = new ConditionalTypeMap()
        private val incompleteTag = StructTag(false, emptyFields, -1)
        //returns the condition under which a structure is complete
        def isComplete(name: String, isUnion: Boolean): FeatureExpr = env.getOrElse((name, isUnion), One(incompleteTag)).when(_.isComplete)
        def isCompleteUnion(name: String) = isComplete(name, true)
        def isCompleteStruct(name: String) = isComplete(name, false)

        def addIncomplete(name: String, isUnion: Boolean, condition: FeatureExpr, scope: Int) = {
            //overwrites complete tags in lower scopes, but has no effects otherwise
            val key = (name, isUnion)
            val prevTag: Conditional[StructTag] = env.getOrElse(key,One(incompleteTag))
            val newTag: Conditional[StructTag] = Choice(condition, One(StructTag(false, emptyFields, scope)), One(incompleteTag))
            val result=ConditionalLib.mapCombination(prevTag, newTag, (p:StructTag, n:StructTag) => if (n.scope > p.scope) n else p)
            new StructEnv(env + (key -> result))
        }


        def addComplete(name: String, isUnion: Boolean, condition: FeatureExpr, fields: ConditionalTypeMap, scope: Int) = {
            // always override previous results, check elsewhere that not replace incorrectly
            val key = (name, isUnion)
            val prevTag: Conditional[StructTag] = env.getOrElse(key,One(incompleteTag))
            val result: Conditional[StructTag] = Choice(condition, One(StructTag(true, fields, scope)), prevTag).simplify
            new StructEnv(env + (key -> result))

//            //TODO check distinct attribute names in each variant
//            //TODO check that there is not both a struct and a union with the same name
//            val key = (name, isUnion)
//            val oldCondition = isComplete(name, isUnion)
//            val oldFields = env.getOrElse(key, (null, new ConditionalTypeMap()))._2
//            val value = (oldCondition or condition, oldFields ++ fields)
//            new StructEnv(env + (key -> value))
        }

        def getFields(name: String, isUnion: Boolean): Conditional[ConditionalTypeMap] = env.getOrElse((name, isUnion),One(incompleteTag)).map(_.fields)

        def getFieldsMerged(name: String, isUnion: Boolean): ConditionalTypeMap =
            getFields(name, isUnion).flatten( (f,a,b)=> a.and(f) ++ b.and(f.not))


//        def get(name: String, isUnion: Boolean): ConditionalTypeMap = env((name, isUnion))._2

        override def toString = env.toString
    }

    //    two possible places:
    //           case e@DeclarationStatement(d) => addDeclaration(d, e)
    //            case e: Declaration => addDeclaration(e, e)


    /**
     * Enum Environment: Just a set of names that are valid enums.
     * No need to remember fields etc, because they are integers anyway and no further checking is done in C
     */

    type EnumEnv = Map[String, FeatureExpr]

    /**
     * label environment: stores which labels are reachable from a goto.
     *
     * the environment is filled upon function entry for the entire function
     * and just stores under which condition a label is defined
     */
    type LabelEnv = Map[String, FeatureExpr]

    /**
     * Typedef env
     *
     * possible in declaration and declaration statement
     */


}