package com.github.badoualy.telegram.tl.builder.poet

import com.github.badoualy.telegram.tl.builder.parser.*
import com.github.badoualy.telegram.tl.builder.poet.utils.*
import com.github.badoualy.telegram.tl.builder.utils.hexString
import com.github.badoualy.telegram.tl.builder.utils.javaEscape
import com.github.badoualy.telegram.tl.builder.utils.lCamelCase
import com.github.badoualy.telegram.tl.builder.utils.uCamelCase
import com.squareup.kotlinpoet.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@Suppress("MemberVisibilityCanPrivate")
class TLClassGenerator(tlDefinition: TLDefinition, val config: Config) {

    val methods = tlDefinition.methods
    val supertypes = tlDefinition.supertypes
    val types = tlDefinition.types

    val executeMethodName = "executeRpcQuery"
    val executeSyncMethodName = "executeRpcQuerySync"

    val typeTypeNameMap = HashMap<TLType, ClassName>()
    val constructorTypeNameMap = HashMap<TLTypeConstructor<*>, ClassName>()

    val apiClass = TypeSpec.makeInterface(TELEGRAM_API_INTERFACE)
    var apiFun: FunSpec.Builder? = null

    val apiSyncClass = TypeSpec.makeInterface(TELEGRAM_SYNC_API_INTERFACE)
    var apiSyncFun: FunSpec.Builder? = null

    val apiWrapperClass = TypeSpec.makeClass(TELEGRAM_API_WRAPPER)
            .addSuperinterfaces(listOf(TYPE_TELEGRAM_API, TYPE_RPC_QUERY_EXECUTOR))
            .addExecutorDelegate(TYPE_RPC_QUERY_EXECUTOR, executeMethodName)
    var apiWrapperFun: FunSpec.Builder? = null

    val apiSyncWrapperClass = TypeSpec.makeClass(TELEGRAM_SYNC_API_WRAPPER)
            .addSuperinterfaces(listOf(TYPE_TELEGRAM_SYNC_API, TYPE_RPC_QUERY_SYNC_EXECUTOR))
            .addExecutorDelegate(TYPE_RPC_QUERY_SYNC_EXECUTOR, executeSyncMethodName)
    var apiSyncWrapperFun: FunSpec.Builder? = null

    fun generate() {
        // TLType to TypeName map
        supertypes
                .map { it.tlType to it.typeName() }
                .toMap(typeTypeNameMap)
        types
                .filterNot { it.hasSupertype }
                .map { it.tlType to it.typeName() }
                .toMap(typeTypeNameMap)

        // TLTypeConstructor to TypeName
        types
                .map { it to it.typeName() }
                .toMap(constructorTypeNameMap)
        methods
                .map { it to it.typeName() }
                .toMap(constructorTypeNameMap)

        generateTypesClasses()
        generateMethodsClasses()

        generateContextClass(TL_API_CONTEXT, types, config.outputMain)
        generateContextClass(TL_API_TEST_CONTEXT, types.union(methods), config.outputTest)
    }

    fun generateTypesClasses() {
        supertypes.forEach {
            it.generateClass().writeToFile(it.tlType.packageName(), config.outputMain)
        }

        types.forEach {
            it.generateClass().writeToFile(it.tlType.packageName(), config.outputMain)
        }
    }

    fun generateMethodsClasses() {
        methods.forEach {
            it.generateMethodClass().writeToFile(PACKAGE_TL_API_REQUEST, config.outputMain)
        }

        apiClass.build().writeToFile(PACKAGE_TL_API, config.outputMain)
        apiWrapperClass.build().writeToFile(PACKAGE_TL_API, config.outputMain)
        apiSyncClass.build().writeToFile(PACKAGE_TL_API, config.outputMain)
        apiSyncWrapperClass.build().writeToFile(PACKAGE_TL_API, config.outputMain)
    }

    private fun generateContextClass(name: String, constructors: Iterable<TLTypeConstructor<*>>, output: String) {
        TypeSpec.objectBuilder(name)
                .applyCommon()
                .superclass(TYPE_TL_CONTEXT)
                .addSuperclassConstructorParameter("%L", types.size)
                .addFunction(FunSpec.makeOverride("init").apply {
                    constructors
                            .map { constructorTypeNameMap[it]!! }
                            .forEach {
                                addStatement("registerClass(%T.CONSTRUCTOR_ID, %T::class.java)",
                                             it, it)
                            }
                }.build())
                .build()
                .writeToFile(PACKAGE_TL_API, output)
    }

    private fun TLAbstractConstructor.generateClass(): TypeSpec {
        val clazz = TypeSpec.makeTLClass(tlClassName())
                .addModifiers(KModifier.ABSTRACT)

        generateClassCommon(clazz)

        // Add list of subclasses in KDoc
        val subtypes = types.filter { it.tlType == tlType }
        clazz.addKdoc("Abstraction level for the following constructors:\n")
                .addKdoc("<ul>\n")
                .apply {
                    subtypes.forEach {
                        addKdoc("<li>")
                        addKdoc("[${it.name}#${it.id.hexString()}]")
                        addKdoc("[${constructorTypeNameMap[it]!!.simpleName()}]")
                        addKdoc("</li>\n")
                    }
                }
                .addKdoc("</ul>\n\n")

        if (forEmptyConstructor) {
            val nonEmptyConstructor = subtypes.find { !it.name.endsWith("empty", true) }!!
            val emptyConstructor = subtypes.find { it.name.endsWith("empty", true) }!!

            clazz
                    .addFunction(FunSpec.builder("isEmpty")
                                         .addModifiers(KModifier.PUBLIC)
                                         .returns(BOOLEAN)
                                         .addStatement("return this is %T",
                                                       constructorTypeNameMap[emptyConstructor]!!)
                                         .build())
                    .addFunction(FunSpec.builder("isNotEmpty")
                                         .addModifiers(KModifier.PUBLIC)
                                         .returns(BOOLEAN)
                                         .addStatement("return this is %T",
                                                       constructorTypeNameMap[nonEmptyConstructor]!!)
                                         .build())
                    .addFunction(FunSpec.builder(
                            "as" + nonEmptyConstructor.name.split(".").last().uCamelCase())
                                         .addModifiers(KModifier.PUBLIC)
                                         .returns(
                                                 constructorTypeNameMap[nonEmptyConstructor]!!.asNullable())
                                         .addStatement("return this as? %T",
                                                       constructorTypeNameMap[nonEmptyConstructor]!!)
                                         .build())
        }

        return clazz.build()
    }

    private fun TLConstructor.generateClass(): TypeSpec {
        val clazz = TypeSpec.makeTLClass(tlClassName(),
                                         if (hasSupertype) typeTypeNameMap[tlType]!!
                                         else TYPE_TL_OBJECT)

        generateClassCommon(clazz)

        return clazz.build()
    }

    fun TLMethod.generateMethodClass(): TypeSpec {
        val responseType = getType(tlType)
        val clazz = TypeSpec.makeTLMethodClass(tlClassName(), responseType)

        val responseFun = FunSpec.makeOverride("deserializeResponse")
                .addParameter("stream", InputStream::class)
                .addParameter("context", TYPE_TL_CONTEXT)
                .addThrows(IOException::class)
                .returns(responseType)

        when (tlType) {
            is TLTypeAny -> {
                clazz.addTypeVariable(TypeVariableName.invoke("T", TYPE_TL_OBJECT))

                // Delegate
                responseFun.addStatement("return query!!.deserializeResponse(stream, context)",
                                         TypeVariableName.invoke("T"))
            }
            is TLTypeGeneric -> {
                val methodName = when (responseType) {
                    TYPE_TL_INT_VECTOR -> "readTLIntVector"
                    TYPE_TL_LONG_VECTOR -> "readTLLongVector"
                    TYPE_TL_STRING_VECTOR -> "readTLStringVector"
                    else -> "readTLVector"
                }
                responseFun.addStatement("return $methodName(stream, context) as %T", responseType)
            }
            else -> {
                val responseCastType = (responseType as? ParameterizedTypeName)?.rawType ?: responseType
                val nullErrorMessage = "Unable to parse response"
                val castErrorMessage = "Incorrect response type, expected \${javaClass.canonicalName}, found \${response.javaClass.canonicalName}"

                responseFun.addStatement(
                        "val response = readTLObject(stream, context) ?: throw %T(%S)",
                        IOException::class.java, nullErrorMessage)
                        .addStatement("return (response as? %T) ?: throw %T(%S)",
                                      responseCastType, IOException::class, castErrorMessage)
            }
        }
        clazz.addFunction(responseFun.build())

        val apiResponseType = ParameterizedTypeName.get(TYPE_SINGLE, responseType)
        apiFun = makeApiFun(apiResponseType)
        apiSyncFun = makeApiFun(responseType)
        apiWrapperFun = makeApiWrapperFun(apiResponseType, executeMethodName)
        apiSyncWrapperFun = makeApiWrapperFun(responseType, executeSyncMethodName)

        generateClassCommon(clazz)

        apiClass.addFunction(apiFun!!.build())
        apiWrapperClass.addFunction(apiWrapperFun!!.build())
        apiSyncClass.addFunction(apiSyncFun!!.build())
        apiSyncWrapperClass.addFunction(apiSyncWrapperFun!!.build())
        apiFun = null
        apiSyncFun = null
        apiWrapperFun = null
        apiSyncWrapperFun = null

        return clazz.build()
    }

    fun TLTypeConstructor<*>.generateClassCommon(clazz: TypeSpec.Builder) {
        var constructorBuilder: FunSpec.Builder? = null
        val id = id

        if (id != null) {
            // CONSTRUCTOR_ID field
            clazz.companionObject(TypeSpec.companionObjectBuilder("")
                                          .addProperty(PropertySpec.builder("CONSTRUCTOR_ID",
                                                                            INT,
                                                                            KModifier.CONST)
                                                               .initializer(
                                                                       "0x${id.hexString()}.toInt()")
                                                               .build())
                                          .build())

            if (parameters.isNotEmpty()) {
                // Empty constructor + constructor with all params
                clazz.emptyConstructor()
                constructorBuilder = FunSpec.constructorBuilder().callThisConstructor()
            }
        }

        // Serialize
        val serializeFun = FunSpec.makeOverride("serializeBody")
                .addThrows(IOException::class)
                .addParameter("stream", OutputStream::class)

        // Unserialize
        val deserializeFun = FunSpec.makeOverride("deserializeBody")
                .addThrows(IOException::class)
                .addParameter("stream", InputStream::class)
                .addParameter("context", TYPE_TL_CONTEXT)

        // Compute serializedSize
        val computeSizeFun = FunSpec.makeOverride("computeSerializedSize")
                .returns(INT)

        // Equals
        val equalsStatements = ArrayList<String>()
        val equalsFun = FunSpec.makeOverride("equals")
                .returns(BOOLEAN)
                .addParameter("other", Any::class.asTypeName().asNullable())
                .apply {
                    val bound = if (parameters.any { it.tlType is TLTypeFunctional }) "<*>" else ""
                    addStatement("if (other !is %T$bound) return false", typeName())
                }
                .addStatement("if (other === this) return true")
                .addCode("\n")

        // Compute flag for serialization
        // TODO: simplify?
        val condParameters = parameters.filter { it.tlType is TLTypeConditional }
        if (condParameters.isNotEmpty() && id != null) {
            val computeFlagsFun = FunSpec.builder("computeFlags")
                    .addModifiers(KModifier.PRIVATE)

            val condBoolean = ArrayList<TLParameter>()

            computeFlagsFun.addStatement("flags = 0")
            for (parameter in condParameters) {
                val tlType = parameter.tlType as TLTypeConditional
                val realType = tlType.realType
                val fieldName = parameter.name.lCamelCase().javaEscape()

                if (realType is TLTypeRaw && arrayOf("true", "false").contains(realType.name)) {
                    computeFlagsFun.addStatement(
                            "flags = if ($fieldName) flags.or(${tlType.pow2Value()}) else flags.and(${tlType.pow2Value()}.inv())")
                    if (condParameters.any {
                        it != parameter
                                && !((it.tlType as TLTypeConditional).realType is TLTypeRaw && (it.tlType.realType as TLTypeRaw).name == "Bool")
                                && (it.tlType as TLTypeConditional).value == tlType.value
                    })
                        condBoolean.add(parameter)
                } else {
                    if (realType is TLTypeRaw && realType.name == "Bool" && condParameters.any { it != parameter && (it.tlType as TLTypeConditional).value == tlType.value }) {
                        computeFlagsFun.addCode("// If field is not serialized force it to false\n")
                        computeFlagsFun.addStatement(
                                "if ($fieldName && (flags and ${tlType.pow2Value()}) == 0) $fieldName = false")
                    } else {
                        computeFlagsFun.addStatement(
                                "flags = if ($fieldName != null) flags.or(${tlType.pow2Value()}) else flags.and(${tlType.pow2Value()}.inv())")
                    }
                }
            }

            if (condBoolean.isNotEmpty()) {
                computeFlagsFun.addCode(
                        "\n// Following parameters might be forced to true by another field that updated the flags\n")
                for (parameter in condBoolean) {
                    computeFlagsFun.addStatement(
                            "" + parameter.name.lCamelCase().javaEscape() + " = (flags and ${(parameter.tlType as TLTypeConditional).pow2Value()}) != 0")
                }
            }

            clazz.addFunction(computeFlagsFun.build())

            serializeFun.addStatement("computeFlags()")
            serializeFun.addCode("\n")

            computeSizeFun.addStatement("computeFlags()")
            computeSizeFun.addCode("\n")
        }

        computeSizeFun.addStatement("var size = SIZE_CONSTRUCTOR_ID")

        // Parameters
        for (parameter in parameters) {
            val fieldTlType = parameter.tlType
            var fieldType = getType(fieldTlType)

            // TODO: check what's for
            if (fieldType is ParameterizedTypeName
                    && (fieldTlType is TLTypeGeneric
                    || (fieldTlType is TLTypeConditional && fieldTlType.realType is TLTypeGeneric))) {
                val typeArg = fieldType.typeArguments.first()
                fieldType = ParameterizedTypeName.get(fieldType.rawType, typeArg)
            }

            // Build field
            val fieldName = parameter.name.lCamelCase().javaEscape()
            if (id != null || parameter.inherited) {
                // TODO: if in flag
                val nullable = when (fieldType) {
                    INT, LONG, FLOAT, DOUBLE, BOOLEAN -> false
                    else -> true
                }
                val realFieldType = if (nullable) fieldType.asNullable() else fieldType
                clazz.addProperty(PropertySpec.varBuilder(fieldName, realFieldType)
                                          .apply {
                                              if (id == null) {
                                                  addModifiers(KModifier.ABSTRACT)
                                              } else {
                                                  if (parameter.inherited)
                                                      addModifiers(KModifier.OVERRIDE)
                                                  initializer(getInitValue(fieldType))
                                              }
                                              if (!fieldTlType.serializable())
                                                  addAnnotation(Transient::class)
                                              if (fieldTlType is TLTypeFlag && (id == null || !parameter.inherited))
                                                  addModifiers(if (id == null) KModifier.PROTECTED
                                                               else KModifier.PRIVATE)
                                          }
                                          .build())
            }

            // Add serialize method entry if not a boolean present only to compute flag
            if (fieldTlType.serializable()) {
                serializeFun.addCode(serializeParameter(fieldName, fieldTlType) + "\n")
                computeSizeFun.addCode(computeSizeParameter(fieldName, fieldTlType) + "\n")
            }

            val deserializeStatement = "$fieldName = ${deserializeParameter(fieldTlType,
                                                                            fieldType)}"
            val count = deserializeStatement.split("%T").size - 1
            deserializeFun.addStatement(deserializeStatement, *Array(count, { fieldType }))

            equalsStatements.add("$fieldName == other.$fieldName")

            // Don't add if flag since it's a computed value
            if (fieldTlType !is TLTypeFlag) {
                // Add constructor parameter
                constructorBuilder?.addParameter(fieldName, fieldType)
                constructorBuilder?.addStatement("this.$fieldName = $fieldName")

                // Add api method
                apiFun?.addParameter(fieldName, fieldType)
                apiWrapperFun?.addParameter(fieldName, fieldType)
                apiSyncFun?.addParameter(fieldName, fieldType)
                apiSyncWrapperFun?.addParameter(fieldName, fieldType)
                if (fieldTlType is TLTypeFunctional) {
                    val typeVariable = TypeVariableName.invoke("T", TYPE_TL_OBJECT)
                    apiFun?.addTypeVariable(typeVariable)
                    apiWrapperFun?.addTypeVariable(typeVariable)
                    apiSyncFun?.addTypeVariable(typeVariable)
                    apiSyncWrapperFun?.addTypeVariable(typeVariable)
                }
            }
        }

        if (id != null) {
            constructorBuilder?.let { clazz.addFunction(it.build()) }
            clazz.addFunction(serializeFun.build())
            clazz.addFunction(deserializeFun.build())
            // TODO: if no parameters, replace by inlined return SIZE_CONSTRUCTOR_ID
            clazz.addFunction(computeSizeFun.addStatement("return size").build())

            // _constructor
            clazz.addProperty(PropertySpec.builder("_constructor", String::class)
                                      .addModifiers(KModifier.PRIVATE)
                                      .initializer("%S", "$name#${id.hexString()}")
                                      .build())

            // toString()
            clazz.addFunction(FunSpec.makeOverride("toString")
                                      .addStatement("return _constructor")
                                      .build())

            // getConstructorId
            // TODO: make this abstract property
            clazz.addFunction(FunSpec.makeOverride("getConstructorId")
                                      .addStatement("return CONSTRUCTOR_ID")
                                      .build())

            if (equalsStatements.isEmpty()) {
                equalsFun.addStatement("return true")
            } else {
                equalsFun.addStatement(equalsStatements.joinToString("\n&& ",
                                                                     prefix = "return "))
            }
            clazz.addFunction(equalsFun.build())
        }
    }

    private fun getType(type: TLType, boxedInTl: Boolean = false): TypeName = when (type) {
        is TLTypeFunctional -> ParameterizedTypeName.get(TYPE_TL_METHOD,
                                                         TypeVariableName.invoke("T"))
        is TLTypeAny -> TypeVariableName.invoke("T")
        is TLTypeFlag -> INT
        is TLTypeConditional -> getType(type.realType)
        is TLTypeGeneric -> {
            when ((type.parameters.first() as TLTypeRaw).name) {
                "int" -> TYPE_TL_INT_VECTOR
                "long" -> TYPE_TL_LONG_VECTOR
                "string" -> TYPE_TL_STRING_VECTOR
                else -> ParameterizedTypeName.get(TYPE_TL_VECTOR,
                                                  getType(type.parameters.first(),
                                                          true))
            }
        }
        is TLTypeRaw -> {
            val name = type.name
            when (name) {
                "int" -> INT
                "long" -> LONG
                "double" -> DOUBLE
                "float" -> FLOAT
                "string" -> String::class.asClassName()
                "bytes" -> TYPE_TL_BYTES
                "Bool", "true", "false" -> if (boxedInTl) TYPE_TL_BOOL else BOOLEAN
                else -> typeTypeNameMap[type] ?: throw RuntimeException("Unknown type $type")
            }
        }
        else -> throw RuntimeException("Unsupported type $type")
    }

    private fun getInitValue(type: TypeName) = when (type) {
        INT -> "0"
        LONG -> "0L"
        FLOAT -> "0F"
        DOUBLE -> "0.0"
        BOOLEAN -> "false"
        else -> "null"
    }

    private fun TypeSpec.Builder.addExecutorDelegate(executorType: TypeName, methodName: String) =
            primaryConstructor(FunSpec.constructorBuilder()
                                       .addParameter("executor", executorType)
                                       .build())
                    .addProperty(PropertySpec.builder("executor", executorType)
                                         .initializer("executor")
                                         .build())
                    .addFunction(FunSpec.makeOverride(methodName)
                                         .addTypeVariable(TypeVariableName.ofTLObject())
                                         .addParameter("method",
                                                       ParameterizedTypeName.get(TYPE_TL_METHOD,
                                                                                 TypeVariableName.T()))
                                         .addStatement("return executor.$methodName(method)")
                                         .build())

    private fun TLMethod.makeApiWrapperFun(responseType: TypeName, methodName: String) =
            FunSpec.makeOverride(name.lCamelCase())
                    .addThrowsByTypename(TYPE_RPC_EXCEPTION, IOException::class.asTypeName())
                    .returns(responseType)
                    .addStatement("return $methodName(%T(%L)) as %T",
                                  typeName(),
                                  if (parameters.isNotEmpty()) {
                                      parameters
                                              .filterNot { it.tlType is TLTypeFlag }
                                              .joinToString(
                                                      ", ") { it.name.lCamelCase().javaEscape() }
                                  } else "",
                                  responseType)

    private fun TLMethod.makeApiFun(responseType: TypeName) =
            FunSpec.builder(name.lCamelCase())
                    .addModifiers(KModifier.ABSTRACT)
                    .addThrowsByTypename(TYPE_RPC_EXCEPTION, IOException::class.asTypeName())
                    .returns(responseType)

    data class Config(val outputMain: String, val outputTest: String)
}