package de.fosd.typechef.typesystem


import de.fosd.typechef.featureexpr.FeatureExpr
import de.fosd.typechef.conditional._
import ConditionalLib._
import de.fosd.typechef.parser.c._

/**
 * parsing types from declarations (top level declarations, parameters, etc)
 *
 * handling of typedef synonyms
 */
trait CDeclTyping extends CTypes with CEnv with CTypeSystemInterface {

    def getExprType(expr: Expr, featureExpr: FeatureExpr, env: Env): Conditional[CType]

    //    def ctype(fun: FunctionDef) = fun -> funType
    //    def ctype(nfun: NestedFunctionDef) = nfun -> nfunType
    //    def ctype(fun: TypeName) = fun -> typenameType

    def getFunctionType(
                           specifiers: List[Opt[Specifier]],
                           declarator: Declarator,
                           oldStyleParameters: List[Opt[OldParameterDeclaration]],
                           featureExpr: FeatureExpr, env: Env): Conditional[CType] = {
        if (!oldStyleParameters.isEmpty) One(CUnknown("alternative parameter notation not supported yet"))
        else if (isTypedef(specifiers)) One(CUnknown("Invalid typedef specificer for function definition (?)"))
        else declType(specifiers, declarator, List(), featureExpr, env)
    }


    def getTypenameType(typename: TypeName, featureExpr: FeatureExpr, env: Env): Conditional[CType] = typename match {
        case TypeName(specs, None) => constructType(specs, featureExpr, env, typename)
        case TypeName(specs, Some(decl)) => getAbstractDeclaratorType(decl, constructType(specs, featureExpr, env, typename), featureExpr, env)
    }


    /**
     * filtering is a workaround for a parsing problem (see open test) that can produce
     * dead AST-subtrees in some combinations.
     *
     * remove when problem is fixed
     */
    protected def filterDeadSpecifiers[T](l: List[Opt[T]], ctx: FeatureExpr): List[Opt[T]] =
        l.filter(o => ((o.feature) and ctx).isSatisfiable)


    def constructType(specifiers: List[Opt[Specifier]], featureExpr: FeatureExpr, env: Env, locationForErrorMsg: AST): Conditional[CType] = {
        val specifiersFiltered = filterDeadSpecifiers(specifiers, featureExpr)
        //unwrap variability
        val exploded: Conditional[List[Specifier]] = explodeOptList(specifiersFiltered)
        Conditional.combine(exploded.mapf(featureExpr, (ctx, specList) => constructTypeOne(specList, ctx, env, locationForErrorMsg))) simplify (featureExpr)
    }


    //TODO variability (not urgent)
    private def hasTransparentUnionAttribute(specifiers: List[Specifier]): Boolean =
        specifiers.exists(isTransparentUnionAttribute(_, FeatureExpr.base))

    private def hasTransparentUnionAttributeOpt(specifiers: List[Opt[Specifier]]): Boolean =
        specifiers.exists(o => isTransparentUnionAttribute(o.entry, o.feature))

    private def isTransparentUnionAttribute(specifier: Specifier, featureContext: FeatureExpr): Boolean =
        specifier match {
            case GnuAttributeSpecifier(attrs) =>
                containsAtomicAttribute(name => (name == "transparent_union") || (name == "__transparent_union__"), attrs)
            case _ => false
        }


    /**variability ignored for now*/
    private def containsAtomicAttribute(f: String => Boolean, attrs: List[Opt[AttributeSequence]]): Boolean =
        (for (Opt(f1, attrSeq) <- attrs; Opt(f2, attr) <- attrSeq.attributes)
        yield attr match {
                case AtomicAttribute(name) => f(name)
                case _ => false
            }).exists((x: Boolean) => x)


    private def constructTypeOne(specifiers: List[Specifier], featureExpr: FeatureExpr, env: Env, locationForErrorMsg: AST): Conditional[CType] = {
        //type specifiers
        var types = List[Conditional[CType]]()
        for (specifier <- specifiers) specifier match {
            case StructOrUnionSpecifier(isUnion, Some(id), _) =>
                if (hasTransparentUnionAttribute(specifiers))
                    types = types :+ One(CIgnore()) //ignore transparent union for now
                else
                    types = types :+ One(CStruct(id.name, isUnion))
            case StructOrUnionSpecifier(isUnion, None, members) =>
                if (hasTransparentUnionAttribute(specifiers))
                    types = types :+ One(CIgnore()) //ignore transparent union for now
                else
                    types = types :+ One(CAnonymousStruct(parseStructMembers(members, featureExpr, env), isUnion))
            case e@TypeDefTypeSpecifier(Id(typedefname)) => {
                val typedefEnvironment = env.typedefEnv
                //typedef name can be shadowed by variable
                val shadow = env.varEnv(typedefname).simplify(featureExpr)
                types = types :+ shadow.mapfr(featureExpr, {
                    case (f, CUnknown(_)) => //normal case: there is no variable by that name
                        if (typedefEnvironment contains typedefname) typedefEnvironment(typedefname)
                        else One(reportTypeError(f, "type not defined " + typedefname, e, Severity.Crash)) //should not occur, because the parser should reject this already. exceptions could be caused by local type declarations
                    case (f, t) =>
                        One(reportTypeError(f, "type " + typedefname + " not defined (shadowed by variable with type " + t + ")", e))
                })
            }
            case EnumSpecifier(_, _) => types = types :+ One(CSigned(CInt())) //TODO check that enum name is actually defined (not urgent, there is not much checking possible for enums anyway)
            case TypeOfSpecifierT(typename) => types = types :+ getTypenameType(typename, featureExpr, env)
            case TypeOfSpecifierU(expr) =>
                types = types :+ getExprType(expr, featureExpr, env)
            case _ =>
        }



        def count(spec: Specifier): Int = specifiers.count(_ == spec)
        def has(spec: Specifier): Boolean = count(spec) > 0

        val isSigned = has(SignedSpecifier())
        val isUnsigned = has(UnsignedSpecifier())
        if (isSigned && isUnsigned)
            return One(reportTypeError(featureExpr, "type both signed and unsigned", specifiers.head))

        //for int, short, etc, always assume signed by default
        def sign(t: CBasicType): One[CType] = One(if (isUnsigned) CUnsigned(t) else CSigned(t))
        //for characters care about SignUnspecified
        def signC(t: CBasicType): One[CType] = One(if (isSigned) CSigned(t) else if (isUnsigned) CUnsigned(t) else CSignUnspecified(t))

        if (has(CharSpecifier()))
            types = types :+ signC(CChar())
        if (count(LongSpecifier()) == 2)
            types = types :+ sign(CLongLong())
        if (count(LongSpecifier()) == 1 && !has(DoubleSpecifier()))
            types = types :+ sign(CLong())
        if (has(ShortSpecifier()))
            types = types :+ sign(CShort())
        if (has(DoubleSpecifier()) && has(LongSpecifier()))
            types = types :+ One(CLongDouble())
        if (has(DoubleSpecifier()) && !has(LongSpecifier()))
            types = types :+ One(CDouble())
        if (has(FloatSpecifier()))
            types = types :+ One(CFloat())
        if ((isSigned || isUnsigned || has(IntSpecifier()) || has(OtherPrimitiveTypeSpecifier("_Bool"))) && !has(ShortSpecifier()) && !has(LongSpecifier()) && !has(CharSpecifier()))
            types = types :+ sign(CInt())

        if (has(VoidSpecifier()))
            types = types :+ One(CVoid())

        //TODO prevent invalid combinations completely?


        if (types.size == 1)
            types.head
        else if (types.size == 0)
            One(reportTypeError(featureExpr, "no type specifier found", locationForErrorMsg))
        else
            One(reportTypeError(featureExpr, "multiple types found " + types, locationForErrorMsg))
    }

    private def noInitCheck = (a: Expr, b: Conditional[CType], c: FeatureExpr, d: Env) => {}


    protected def checkStructsC(ctype: Conditional[CType], expr: FeatureExpr, env: Env, where: AST, checkedStructs: List[String] = Nil): Unit = ctype mapf(expr, {
        (f, t) => checkStructs(t, f, env, where, checkedStructs)
    })

    /**
     *       if we declare a variable or array (not pointer or function)
     *       ensure that structs are resolvable
     */
    protected def checkStructs(ctype: CType, expr: FeatureExpr, env: Env, where: AST, checkedStructs: List[String] = Nil): Unit = ctype match {
        case CObj(t) => checkStructs(t, expr, env, where, checkedStructs)
        case CArray(t, _) => checkStructs(t, expr, env, where, checkedStructs)
        case CStruct(name, isUnion) =>
            val declExpr = env.structEnv.isDefined(name, isUnion)
            if ((expr andNot declExpr).isSatisfiable)
                reportTypeError(expr andNot declExpr, (if (isUnion) "Union " else "Struct ") + name + " not defined. (defined only in context " + declExpr + ")", where, Severity.TypeLookupError)
        case CAnonymousStruct(fields, _) => //check fields
            val fieldTypes = fields.keys.map(k => fields.getOrElse(k, CUnknown()))
            fieldTypes.map(ct => checkStructsC(ct, expr, env, where, checkedStructs))
        case _ =>
    }

    /**
     * under which condition is modifier extern defined?
     */
    def getIsExtern(list: List[Opt[Specifier]]): FeatureExpr =
        list.filter(_.entry == ExternSpecifier()).map(_.feature).fold(FeatureExpr.dead)(_ or _)

    def getDeclaredVariables(decl: Declaration, featureExpr: FeatureExpr, env: Env,
                             checkInitializer: (Expr, Conditional[CType], FeatureExpr, Env) => Unit = noInitCheck
                                ): List[(String, FeatureExpr, Conditional[CType])] = {
        val enumDecl = enumDeclarations(decl.declSpecs, featureExpr)
        val isExtern = getIsExtern(decl.declSpecs)
        var eenv = env.addVars(enumDecl, false, env.scope)
        val varDecl = if (isTypedef(decl.declSpecs)) List() //no declaration for a typedef
        else {
            val returnType: Conditional[CType] = constructType(decl.declSpecs, featureExpr, eenv, decl)

            for (Opt(f, init) <- decl.init) yield {
                val ctype = filterTransparentUnion(getDeclaratorType(init.declarator, returnType, featureExpr and f, eenv), init.attributes).simplify(featureExpr and f)
                eenv = eenv.addVar(init.getName, featureExpr and f, ctype, false, env.scope)
                init.getExpr map {
                    checkInitializer(_, ctype, featureExpr and f, eenv)
                }

                //if we declare a variable or array (not pointer or function)
                //ensure that structs are resolvable
                checkStructsC(ctype, featureExpr and f andNot isExtern, env, decl)


                (init.declarator.getName, featureExpr and f, ctype)
            }
        }
        enumDecl ++ varDecl
    }

    //replace union types by CIgnore if attribute transparent_union is set
    private def filterTransparentUnion(t: Conditional[CType], attributes: List[Opt[AttributeSpecifier]]) =
        t.map({
            case x@CStruct(_, true) =>
                if (hasTransparentUnionAttributeOpt(attributes))
                    CIgnore()
                else x
            case x@CAnonymousStruct(_, true) =>
                if (hasTransparentUnionAttributeOpt(attributes))
                    CIgnore()
                else x
            case x => x
        })

    /**define all fields from enum type specifiers as int values
     *
     * important: this recurses into structures!
     **/
    private def enumDeclarations(specs: List[Opt[Specifier]], featureExpr: FeatureExpr): List[(String, FeatureExpr, Conditional[CType])] = {
        var result = List[(String, FeatureExpr, Conditional[CType])]()
        for (Opt(f, spec) <- specs) spec match {
            case EnumSpecifier(_, Some(enums)) => for (Opt(f2, enum) <- enums)
                result = (enum.id.name, featureExpr and f and f2, One(CSigned(CInt()))) :: result
            //recurse into structs
            case StructOrUnionSpecifier(_, _, fields) =>
                for (Opt(f2, structDeclaration) <- fields)
                    result = result ++ enumDeclarations(structDeclaration.qualifierList, featureExpr and f and f2)
            case _ =>
        }
        result
    }


    def declType(specs: List[Opt[Specifier]], decl: Declarator, attributes: List[Opt[AttributeSpecifier]], featureExpr: FeatureExpr, env: Env): Conditional[CType] = {
        var ctype = getDeclaratorType(decl, constructType(specs, featureExpr, env, decl), featureExpr, env)
        //postprocessing filters for __attribute__
        ctype = filterTransparentUnion(ctype, attributes)
        if (contains__mode__attribute(attributes))
            ctype = One(CIgnore())
        ctype
    }


    /**
     * checks whether there is any __mode__ attribute
     *
     * workaround for currently not supported __mode__ attribute in typedefs,
     * see http://www.delorie.com/gnu/docs/gcc/gcc_80.html
     *
     * ignores variability right now, very conservative
     */
    private def contains__mode__attribute(attrs: List[Opt[AttributeSpecifier]]): Boolean =
        attrs.exists(
            oattr => oattr.entry match {
                case GnuAttributeSpecifier(attrSeqList) => containsAtomicAttribute(_ == "__mode__", attrSeqList)
                case _ => false
            }
        )

    // assumptions: we expect that a typedef specifier is either always included or never
    def isTypedef(specs: List[Opt[Specifier]]) = specs.map(_.entry).contains(TypedefSpecifier())


    //shorthand
    protected def getDeclarationType(specifiers: List[Opt[Specifier]], decl: Declarator, featureExpr: FeatureExpr, env: Env) =
        getDeclaratorType(decl, constructType(specifiers, featureExpr, env, decl), featureExpr, env)

    protected def getDeclaratorType(decl: Declarator, returnType: Conditional[CType], featureExpr: FeatureExpr, env: Env): Conditional[CType] = {
        val rtype = decorateDeclaratorExt(decorateDeclaratorPointer(returnType, decl.pointers), decl.extensions, featureExpr, env)

        //this is an absurd order but seems to be as specified
        //cf. http://www.ericgiguere.com/articles/reading-c-declarations.html
        decl match {
            case AtomicNamedDeclarator(ptrList, name, e) => rtype
            case NestedNamedDeclarator(ptrList, innerDecl, e) => getDeclaratorType(innerDecl, rtype, featureExpr, env)
        }
    }

    private def getAbstractDeclaratorType(decl: AbstractDeclarator, returnType: Conditional[CType], featureExpr: FeatureExpr, env: Env): Conditional[CType] = {
        val rtype = decorateDeclaratorExt(decorateDeclaratorPointer(returnType, decl.pointers), decl.extensions, featureExpr, env)

        //this is an absurd order but seems to be as specified
        //cf. http://www.ericgiguere.com/articles/reading-c-declarations.html
        decl match {
            case AtomicAbstractDeclarator(ptrList, e) => rtype
            case NestedAbstractDeclarator(ptrList, innerDecl, e) => getAbstractDeclaratorType(innerDecl, rtype, featureExpr, env)
        }
    }

    private def decorateDeclaratorExt(t: Conditional[CType], extensions: List[Opt[DeclaratorExtension]], featureExpr: FeatureExpr, env: Env): Conditional[CType] =
        conditionalFoldRightFR(extensions.reverse, t, featureExpr,
            (fexpr: FeatureExpr, ext: DeclaratorExtension, rtype: CType) => ext match {
                case o@DeclIdentifierList(idList) => One(
                    if (idList.isEmpty) CFunction(List(), rtype)
                    else {
                        assertTypeSystemConstraint(false, fexpr, "cannot derive type of function in this style yet", o);
                        CUnknown("unsupported")
                    })
                case DeclParameterDeclList(parameterDecls) =>
                    var paramLists: Conditional[List[CType]] =
                        ConditionalLib.explodeOptList(getParameterTypes(parameterDecls, fexpr, env))
                    paramLists.map(CFunction(_, rtype))
                case DeclArrayAccess(expr) => One(CArray(rtype))
            }
        )

    private def decorateDeclaratorPointer(t: Conditional[CType], pointers: List[Opt[Pointer]]): Conditional[CType] =
        ConditionalLib.conditionalFoldRight(pointers, t, (a: Pointer, b: CType) => CPointer(b))


    private def getParameterTypes(parameterDecls: List[Opt[ParameterDeclaration]], featureExpr: FeatureExpr, env: Env): List[Opt[CType]] = {
        val r: List[Opt[Conditional[CType]]] = for (Opt(f, param) <- parameterDecls) yield param match {
            case p@PlainParameterDeclaration(specifiers) => Opt(f, constructType(specifiers, featureExpr and f, env, p))
            case p@ParameterDeclarationD(specifiers, decl) => Opt(f, getDeclaratorType(decl, constructType(specifiers, featureExpr and f, env, p), featureExpr and f, env))
            case p@ParameterDeclarationAD(specifiers, decl) => Opt(f, getAbstractDeclaratorType(decl, constructType(specifiers, featureExpr and f, env, p), featureExpr and f, env))
            case VarArgs() => Opt(f, One(CVarArgs()))
        }
        Conditional.flatten(r)
    }

    def addStructDeclarationToEnv(e: StructDeclaration, featureExpr: FeatureExpr, env: Env): StructEnv;

    def parseStructMembers(members: List[Opt[StructDeclaration]], featureExpr: FeatureExpr, env: Env): ConditionalTypeMap = {
        var result = new ConditionalTypeMap()
        for (Opt(f, structDeclaration) <- members) {
            for (Opt(g, structDeclarator) <- structDeclaration.declaratorList)
                structDeclarator match {
                    case StructDeclarator(decl, _, attr) =>
                        val ctype = declType(structDeclaration.qualifierList, decl, attr, featureExpr and f and g, env)
                        //for nested structs, we need to add the inner structs to the environment
                        val env2 = env.updateStructEnv(addStructDeclarationToEnv(structDeclaration, featureExpr, env))
                        checkStructsC(ctype, featureExpr and f and g, env2, structDeclaration)
                        result = result +(decl.getName, f and g, ctype)
                    case StructInitializer(expr, _) => //TODO check: ignored for now, does not have a name, seems not addressable. occurs for example in struct timex in async.i test
                }
            //for unnamed fields, if they are struct or union inline their fields
            //cf. http://gcc.gnu.org/onlinedocs/gcc/Unnamed-Fields.html#Unnamed-Fields
            if (structDeclaration.declaratorList.isEmpty) constructType(structDeclaration.qualifierList, featureExpr and f, env, structDeclaration) match {
                case One(CAnonymousStruct(fields, _)) => result = result ++ fields
                //                case CStruct(name, _) => //TODO inline as well
                case _ => //don't care about other types
            }
        }
        result
    }

    //
    //
    //    /*************
    //     * Typedef environment (all defined type synonyms up to here)
    //     */
    //    /**typedef enviroment, outside the current declaration*/
    //    val previousTypedefEnv: AST ==> ConditionalTypeMap = {
    //        case ast: AST =>
    //            if (!(ast -> inDeclaration)) ast -> typedefEnv
    //            else (ast -> outerDeclaration -> prevOrParentAST -> typedefEnv)
    //    }
    //
    //
    //    protected def findOutermostDeclaration(a: AST): AST = findOutermostDeclaration(a, null)
    //    protected def findOutermostDeclaration(a: AST, last: AST): AST = a match {
    //        case decl: Declaration => findOutermostDeclaration(decl -> parentAST, decl)
    //        case decl: DeclarationStatement => findOutermostDeclaration(decl -> parentAST, decl)
    //        case a: AST => findOutermostDeclaration(a -> parentAST, last)
    //        case null => last
    //    }
    //
    //    val typedefEnv: AST ==> ConditionalTypeMap = attr {
    //        case e: Declaration => outerTypedefEnv(e) ++ recognizeTypedefs(e)
    //        case e@DeclarationStatement(d) =>
    //            outerTypedefEnv(e) ++ recognizeTypedefs(d)
    //        case e: AST => outerTypedefEnv(e)
    //    }
    //    private def outerTypedefEnv(e: AST): ConditionalTypeMap =
    //        outer[ConditionalTypeMap](typedefEnv, () => new ConditionalTypeMap(), e)
    //
    protected def recognizeTypedefs(decl: Declaration, featureExpr: FeatureExpr, env: Env): Seq[(String, FeatureExpr, Conditional[CType])] = {
        if (isTypedef(decl.declSpecs))
            (for (Opt(f, init) <- decl.init) yield
                (init.getName, featureExpr and f,
                    declType(decl.declSpecs, init.declarator, init.attributes, featureExpr and f, env)))
        else Seq()
    }

    //
    //    val inDeclarationOrDeclStmt: AST ==> Boolean = attr {
    //        case e: DeclarationStatement => true
    //        case e: Declaration => true
    //        case e: AST => if (e -> parentAST == null) false else e -> parentAST -> inDeclarationOrDeclStmt
    //    }
    //    val inDeclaration: AST ==> Boolean = attr {
    //        case e: Declaration => true
    //        case e: AST => if (e -> parentAST == null) false else e -> parentAST -> inDeclaration
    //    }
    //    val inDeclaratorOrSpecifier: AST ==> Boolean = attr {
    //        case e: Declarator => true
    //        case e: Specifier => true
    //        case e: AST => if (e -> parentAST == null) false else e -> parentAST -> inDeclaratorOrSpecifier
    //    }
    //    //get the first parent node that is a declaration
    //    private val outerDeclaration: AST ==> Declaration = attr {
    //        case e: Declaration => e
    //        case e: AST => if ((e -> parentAST) == null) null else e -> parentAST -> outerDeclaration
    //    }


}