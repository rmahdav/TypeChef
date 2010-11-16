package de.fosd.typechef.featureexpr
import java.io.PrintWriter
import de.fosd.typechef.featureexpr.LazyLib.Susp

object MacroContext {
    private var flagFilters = List((x: String) => true) //return true means flag can be specified by user, false means it is undefined initially
    def setPrefixFilter(prefix: String) {
        flagFilters = ((x: String) => !x.startsWith(prefix)) :: flagFilters
    }
    def setPostfixFilter(postfix: String) {
        flagFilters = ((x: String) => !x.endsWith(postfix)) :: flagFilters
    }
    def setPrefixOnlyFilter(prefix: String) {
        flagFilters = ((x: String) => x.startsWith(prefix)) :: flagFilters
    }
    def flagFilter(x: String) = flagFilters.forall(_(x))

}

import FeatureExpr.createDefinedExternal
/**
 * represents the knowledge about macros at a specific point in time time
 * 
 * knownMacros contains all macros but no duplicates
 * 
 * by construction, all alternatives are mutually exclusive (but do not necessarily add to BASE)
 */
class MacroContext(knownMacros: Map[String, Macro], var cnfCache: Map[String, (String, Susp[NF])]) extends FeatureProvider {
    /**
     * when true, only CONFIG_ flags can be defined externally (simplifies the handling signficiantly)
     */

    def this() = { this(Map(), Map()) }
    def define(name: String, infeature: FeatureExpr, other: Any): MacroContext = {
        val feature = infeature //.resolveToExternal()
        val newMC = new MacroContext(
            knownMacros.get(name) match {
                case Some(macro) => knownMacros.updated(name, macro.addNewAlternative(new MacroExpansion(feature, other)))
                case None => {
                    val initialFeatureExpr = if (MacroContext.flagFilter(name))
                        feature.or(createDefinedExternal(name))
                    else
                        feature
                    knownMacros + ((name, new Macro(name, initialFeatureExpr, List(new MacroExpansion(feature, other)))))
                }
            }, cnfCache - name)
        println("#define " + name)
        newMC
    }

    def undefine(name: String, infeature: FeatureExpr): MacroContext = {
        val feature = infeature //.resolveToExternal()
        new MacroContext(
            knownMacros.get(name) match {
                case Some(macro) => knownMacros.updated(name, macro.andNot(feature))
                case None => knownMacros + ((name, new Macro(name, feature.not().and(createDefinedExternal(name)), List())))
            }, cnfCache - name)
    }

    def getMacroCondition(feature: String): FeatureExpr = {
        knownMacros.get(feature) match {
            case Some(macro) => macro.getFeature()
            case None =>
                if (MacroContext.flagFilter(feature))
                    createDefinedExternal(feature)
                else
                    FeatureExpr.dead
        }
    }

    /**
     * this returns a condition for the SAT solver in CNF in the following
     * form
     * 
     * (newMacroName, DefinedExternal(newMacroName) <=> getMacroCondition)
     * 
     * the result is cached. $$ is later replaced by a name for the SAT solver
     */
    def getMacroConditionCNF(name: String): (String, Susp[NF]) = {
        if (cnfCache.contains(name))
            return cnfCache(name)

        val newMacroName = name + "$$" + MacroIdGenerator.nextMacroId
        val c = getMacroCondition(name)
        val d = FeatureExpr.createDefinedExternal(newMacroName)
        val condition = FeatureExpr.createEquiv(c, d)
        val cnf = LazyLib.delay(condition.toEquiCNF)
        val result = (newMacroName, cnf)
        cnfCache = cnfCache + (name -> result)
        result
    }

    def isFeatureDead(feature: String): Boolean = getMacroCondition(feature).isDead()

    def isFeatureBase(feature: String): Boolean = getMacroCondition(feature).isBase()

    def getMacroExpansions(identifier: String): Array[MacroExpansion] =
        knownMacros.get(identifier) match {
            case Some(macro) => macro.getOther().toArray
            case None => Array()
        }
    def getApplicableMacroExpansions(identifier: String, currentPresenceCondition: FeatureExpr): Array[MacroExpansion] =
        getMacroExpansions(identifier).filter(m => !currentPresenceCondition.and(m.getFeature()).isDead());

    override def toString() = { knownMacros.values.mkString("\n\n\n") + printStatistics }
    def debugPrint(writer: PrintWriter) {
      knownMacros.values.foreach(x => {
          writer print x; writer print "\n\n\n"
        })
      writer print printStatistics
    }
    def printStatistics =
        "\n\n\nStatistics (macros,macros with >1 alternative expansions,>2,>3,>4,non-trivial presence conditions,number of distinct configuration flags):\n" +
            knownMacros.size + ";" +
            knownMacros.values.filter(_.numberOfExpansions > 1).size + ";" +
            knownMacros.values.filter(_.numberOfExpansions > 2).size + ";" +
            knownMacros.values.filter(_.numberOfExpansions > 3).size + ";" +
            knownMacros.values.filter(_.numberOfExpansions > 4).size + ";" +
            knownMacros.values.filter(!_.getFeature.isTautology).size + "\n"
    //    	+getNumberOfDistinctFlagsStatistic+"\n";
    //    private def getNumberOfDistinctFlagsStatistic = {
    //    	var flags:Set[String]=Set()
    //    	for (macro<-knownMacros.values)
    //    		macro.getFeature.accept(node=>{
    //    			node match {
    //    				case DefinedExternal(name) => flags=flags+name
    //    				case _=>
    //    			}
    //    		})
    //    	flags.size
    //    }

    private def getMacro(name: String) = knownMacros(name)
}

/**
 * name: name of the macro
 * feature: condition under which any of the macro definitions is visible
 * featureExpansions: a list of macro definions and the condition under which they are visible (should be mutually exclusive by construction)
 */
private class Macro(name: String, feature: FeatureExpr, var featureExpansions: List[MacroExpansion]) {
    def getName() = name;
    def getFeature() = feature;
    def getOther() = {
        //lazy filtering
        featureExpansions = featureExpansions.filter(!_.getFeature().isContradiction())
        featureExpansions;
    }
    def addNewAlternative(exp: MacroExpansion): Macro =
        //note addExpansion changes presence conditions of existing expansions
        new Macro(name, feature.or(exp.getFeature()), addExpansion(exp))

    /**
     * add an expansion (either by extending an existing one or by adding a new one). 
     * the scope of all others is restricted accordingly
     */
    private def addExpansion(exp: MacroExpansion): List[MacroExpansion] = {
        var found = false;
        val modifiedExpansions = featureExpansions.map(other =>
            if (exp.getExpansion() == other.getExpansion()) {
                found = true
                other.extend(exp)
            } else
                other.andNot(exp.getFeature()))
        if (found) modifiedExpansions else exp :: modifiedExpansions
    }
    def andNot(expr: FeatureExpr): Macro =
        new Macro(name, feature and (expr.not), featureExpansions.map(_.andNot(expr)));
    //  override def equals(that:Any) = that match { case m:Macro => m.getName() == name; case _ => false; }
    override def toString() = "#define " + name + " if " + feature.toString + " \n\texpansions \n" + featureExpansions.mkString("\n")
    def numberOfExpansions = featureExpansions.size
}

class MacroExpansion(feature: FeatureExpr, expansion: Any /* Actually, MacroData from PartialPreprocessor*/ ) {
    def getFeature(): FeatureExpr = feature
    def getExpansion(): Any = expansion
    def andNot(expr: FeatureExpr): MacroExpansion = new MacroExpansion(feature and (expr.not), expansion)
    override def toString() = "\t\t" + expansion.toString() + " if " + feature.toString
    //if the other has the same expansion, merge features as OR
    def extend(other: MacroExpansion): MacroExpansion =
        new MacroExpansion(feature.or(other.getFeature()), expansion)
}

object MacroIdGenerator {
    var macroId = 0
    def nextMacroId = {
        macroId = macroId + 1
        macroId
    }
}
