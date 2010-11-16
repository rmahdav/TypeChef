package de.fosd.typechef.featureexpr
import de.fosd.typechef.featureexpr.LazyLib.Susp

import org.sat4j.core.VecInt;
import org.sat4j.minisat.SolverFactory;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.IProblem;
import org.sat4j.specs.ISolver;
import org.sat4j.specs.IVecInt;
import org.sat4j.specs.IteratorInt;
import org.sat4j.specs.TimeoutException;
import org.sat4j.tools.ConstrGroup;
import org.sat4j.tools.ModelIterator;
import org.sat4j.tools.RemiUtils;
import org.sat4j.tools.SolutionCounter;

class SatSolver extends Solver {

    private def countClauses(expr: NF) = expr.clauses.size

    private def countFlags(expr: NF) = {
        var flags = Set[String]()
        for (clause <- expr.clauses)
            for (literal <- (clause.posLiterals ++ clause.negLiterals))
                flags = flags + literal.satName
        flags.size
    }

    val PROFILING = true;

    def isSatisfiable(exprCNF: NF): Boolean = {
        if (exprCNF.isEmpty) return true
        if (exprCNF.isFull) return false

        val startTime = System.currentTimeMillis();

        if (PROFILING)
            print("<SAT " + countClauses(exprCNF) + " with " + countFlags(exprCNF) + " flags ")

        val startTimeSAT = System.currentTimeMillis();
        try {

            val solver = SolverFactory.newDefault();
            //        solver.setTimeoutMs(1000);
            solver.setTimeoutOnConflicts(100000)

            //find used macros, combine them by common expansion
            val cnfs: List[NF] = prepareFormula(exprCNF)

            if (PROFILING)
                print(";")
            var uniqueFlagIds: Map[String, Int] = Map();
            for (cnf <- cnfs; clause <- cnf.clauses)
                for (literal <- (clause.posLiterals ++ clause.negLiterals))
                    if (!uniqueFlagIds.contains(literal.satName))
                        uniqueFlagIds = uniqueFlagIds + ((literal.satName, uniqueFlagIds.size + 1))
            if (PROFILING)
                print(";" + uniqueFlagIds.size)

            solver.newVar(uniqueFlagIds.size)

            def addClauses(cnfs: List[NF]): Boolean = {
                try {
                    for (cnf <- cnfs; clause <- cnf.clauses; if !clause.isEmpty)
                        addClause(clause);
                } catch {
                    case e: ContradictionException => return true;
                }
                false
            }
            def addClause(clause: Clause): Unit = {
                val clauseArray: Array[Int] = new Array(clause.size)
                var i = 0
                for (literal <- clause.posLiterals) {
                    clauseArray(i) = uniqueFlagIds(literal.satName)
                    i = i + 1;
                }
                for (literal <- clause.negLiterals) {
                    clauseArray(i) = -uniqueFlagIds(literal.satName)
                    i = i + 1;
                }
                solver.addClause(new VecInt(clauseArray));
            }

            var contradiction = addClauses(cnfs)
            if (PROFILING)
                print(";")
            return !contradiction && solver.isSatisfiable();

        } finally {
            if (PROFILING)
                println(" in " + (System.currentTimeMillis() - startTimeSAT) + " ms>")
        }
    }

    /**
     * DefinedExternal translates directly into a flag for the SAT solver
     * 
     * DefinedMacro is more complicated, because it is equivalent to a whole formula.
     * to avoid transforming a large formula into CNF, we use the following strategy
     * 
     * DefinedMacro("X",expr) expands to the following
     * DefinedExternal(freshName) -- and a new formula DefinedExternal(freshName) <=> expr
     * Both are independent clauses fed to the SAT solver
     * 
     * Actually, DefinedMacro already contains an expression name <=> expr as CNF, where we
     * just need to replace the Macro name by a fresh name. 
     * 
     * We first collect all expansions and detect identical ones             * 
     */
    def prepareFormula(expr: NF): List[NF] = {

        var macroExpansions: Map[String, NF] = Map()

        def prepareLiteral(literal: DefinedExpr): DefinedExpr = {
            literal match {
                case DefinedMacro(name, _, expansionName, expansion) => {
                    if (!macroExpansions.contains(expansionName)) {
                        if (PROFILING)
                            print(name)
                        val e = expansion()
                        if (PROFILING)
                            print(":")
                        //recursively expand formula
                        macroExpansions = macroExpansions + (expansionName -> prepareFormula(expansion()))
                        if (PROFILING)
                            print(".")
                    }
                    DefinedExternal(expansionName)
                }
                case e => e
            }
        }
        def prepareClause(clause: Clause): Clause = {
        	clause.substitute(prepareLiteral(_))
        }
        def prepareFormula(formula: NF): NF = {
            new NF(formula.clauses.map(prepareClause(_)), formula.isFull)
        }

        val targetExpr = prepareFormula(expr)
        List(targetExpr) ++ macroExpansions.values
    }

}
