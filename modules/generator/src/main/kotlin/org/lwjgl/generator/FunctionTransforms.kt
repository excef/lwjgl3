package org.lwjgl.generator

import java.io.PrintWriter

interface FunctionTransform<T : QualifiedType> {
	fun transformDeclaration(param: T, original: String): String?
	fun transformCall(param: T, original: String): String
}

/** A function transform that must generate additional code. */
interface CodeFunctionTransform<T : QualifiedType> {
	fun generate(qtype: T, code: Code): Code
}

/** A function transform that makes use of the stack. */
interface StackFunctionTransform<T : QualifiedType> {
	fun setupStack(func: Function, qtype: T, writer: PrintWriter)
}

/** Marker interface to indicate that pointer and buffer checks should be skipped. */
interface SkipCheckFunctionTransform

internal fun <T : QualifiedType> T.transformDeclarationOrElse(transforms: Map<QualifiedType, FunctionTransform<out QualifiedType>>, original: String): String? {
	val transform = transforms[this]
	if ( transform == null )
		return original
	else
		@Suppress("UNCHECKED_CAST")
		return (transform as FunctionTransform<T>).transformDeclaration(this, original)
}

internal fun <T : QualifiedType> T.transformCallOrElse(transforms: Map<QualifiedType, FunctionTransform<out QualifiedType>>, original: String): String {
	val transform = transforms[this]
	if ( transform == null )
		return original
	else
		@Suppress("UNCHECKED_CAST")
		return (transform as FunctionTransform<T>).transformCall(this, original)
}

internal open class AutoSizeTransform(
	val bufferParam: Parameter,
	val applyTo: ApplyTo,
	val applyFactor: Boolean = true
) : FunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String) = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String): String {
		if ( applyTo === ApplyTo.NORMAL )
			return param.name

		var expression = if ( bufferParam.nativeType is ArrayType )
			"${bufferParam.name}.length"
		else
			"${bufferParam.name}.remaining()"
		val factor = param[AutoSize].factor
		if ( applyFactor && factor != null )
			expression += " ${factor.expression()}"

		if ( bufferParam has nullable )
			expression = "${bufferParam.name} == null ? 0 : $expression"

		if ( (param.nativeType.mapping as PrimitiveMapping).bytes != 4 )
			expression = "(${param.nativeType.javaMethodType})${if ( expression.contains(' ') ) "($expression)" else expression}"

		return expression
	}
}

internal fun AutoSizeTransform(bufferParam: Parameter, applyTo: ApplyTo, byteShift: String) =
	if ( byteShift == "0" ) AutoSizeTransform(bufferParam, applyTo) else AutoSizeBytesTransform(bufferParam, applyTo, byteShift)

private class AutoSizeBytesTransform(bufferParam: Parameter, applyTo: ApplyTo, val byteShift: String) : AutoSizeTransform(bufferParam, applyTo) {
	override fun transformCall(param: Parameter, original: String): String {
		if ( applyTo === ApplyTo.NORMAL )
			return param.name

		var expression = "${bufferParam.name}.remaining()"
		val factor = param[AutoSize].factor
		if ( factor == null )
			expression = "$expression << $byteShift"
		else if ( applyTo !== ApplyTo.ALTERNATIVE ) // Hack to skip the expression with MultiType
			expression = "($expression ${factor.expression()}) << $byteShift"

		if ( bufferParam has nullable )
			expression = "(${bufferParam.name} == null ? 0 : $expression)"

		if ( (param.nativeType.mapping as PrimitiveMapping).bytes < 4 )
			expression = "(${param.nativeType.javaMethodType})($expression)"

		return expression
	}
}

internal open class AutoSizeCharSequenceTransform(val bufferParam: Parameter) : FunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String) = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String): String {
		var expression = if ( bufferParam has nullable )
			"${bufferParam.name} == null ? 0 : ${bufferParam.name}Encoded.remaining()"
		else
			"${bufferParam.name}EncodedLen"

		if ( param[AutoSize].factor != null )
			expression = "$expression ${param[AutoSize].factor!!.expression()}"

		if ( (param.nativeType.mapping as PrimitiveMapping).bytes < 4 )
			expression = "(${param.nativeType.javaMethodType})($expression)"

		return expression
	}
}

internal class AutoTypeParamTransform(val autoType: String) : FunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String) = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String) = autoType // Replace with hard-coded type
}

internal class AutoTypeTargetTransform(val autoType: PointerMapping) : FunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String) = "${autoType.javaMethodType.simpleName} ${param.name}"
	override fun transformCall(param: Parameter, original: String) = original
}

internal open class ExpressionTransform(
	val expression: String,
	val keepParam: Boolean = false,
	val paramTransform: FunctionTransform<Parameter>? = null
) : FunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String) =
		if ( keepParam ) paramTransform?.transformDeclaration(param, original) ?: original else null

	override fun transformCall(param: Parameter, original: String) = expression
}

internal class ExpressionLocalTransform(
	expression: String,
	keepParam: Boolean = false
) : ExpressionTransform(expression, keepParam), CodeFunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformCall(param: Parameter, original: String) = original
	override fun generate(qtype: Parameter, code: Code) = code.append(
		javaInit = statement("\t\t${qtype.javaMethodType} ${qtype.name} = $expression;", ApplyTo.ALTERNATIVE)
	)
}

internal class CharSequenceTransform(
	val nullTerminated: Boolean
) : FunctionTransform<Parameter>, StackFunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String) = "CharSequence ${param.name}"
	override fun transformCall(param: Parameter, original: String) = if ( param has nullable )
		"memAddressSafe(${param.name}Encoded)"
	else
		"memAddress(${param.name}Encoded)"

	override fun setupStack(func: Function, qtype: Parameter, writer: PrintWriter) {
		writer.print("\t\t\tByteBuffer ${qtype.name}Encoded = ")
		if ( qtype has nullable )
			writer.print("${qtype.name} == null ? null : ")
		writer.print("stack.${(qtype.nativeType as CharSequenceType).charMapping.charset}(${qtype.name}")
		if ( !nullTerminated )
			writer.print(", false")
		writer.println(");")
		if ( !nullTerminated )
			writer.println("\t\t\tint ${qtype.name}EncodedLen = ${qtype.name}Encoded.capacity();")
	}
}

internal object StringReturnTransform : FunctionTransform<ReturnValue> {
	override fun transformDeclaration(param: ReturnValue, original: String) = "String"
	override fun transformCall(param: ReturnValue, original: String): String {
		val expression = if ( original.startsWith("memByteBufferNT") )
			original.substring(17, original.length - 1);
		else
			original
		return "mem${(param.nativeType as CharSequenceType).charMapping.charset}($expression)";
	}
}

internal class PrimitiveValueReturnTransform(
	val bufferType: PointerMapping,
	val paramName: String
) : FunctionTransform<ReturnValue>, StackFunctionTransform<ReturnValue> {
	override fun transformDeclaration(param: ReturnValue, original: String) = bufferType.primitive.let { if ( it == "pointer" ) "long" else it } // Replace void with the buffer value type
	override fun transformCall(param: ReturnValue, original: String) = "\t\treturn $paramName.get(0);" // Replace with value from the stack
	override fun setupStack(func: Function, qtype: ReturnValue, writer: PrintWriter) = writer.println("\t\t\t${bufferType.box}Buffer $paramName = stack.calloc${bufferType.mallocType}(1);")
}

internal object PrimitiveValueTransform : FunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String) = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String) = "memAddress(${param.name})" // Replace with stack buffer
}

internal object Expression1Transform : FunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String) = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String) = "1" // Replace with 1
}

internal class SingleValueTransform(
	val paramType: String,
	val elementType: String,
	val newName: String
) : FunctionTransform<Parameter>, StackFunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String) = "$paramType $newName" // Replace with element type + new name
	override fun transformCall(param: Parameter, original: String) = "memAddress(${param.name})" // Replace with stack buffer
	override fun setupStack(func: Function, qtype: Parameter, writer: PrintWriter) = writer.println("\t\t\t${qtype.javaMethodType} ${qtype.name} = stack.${elementType}s($newName);")
}
internal class SingleValueStructTransform(
	val newName: String
) : FunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String) = "${param.nativeType.javaMethodType} $newName" // Replace with element type + new name
	override fun transformCall(param: Parameter, original: String): String = "$newName.address()"
}

internal class VectorValueTransform(
	val paramType: PointerMapping,
	val elementType: String,
	val newName: String,
	val size: Int
) : FunctionTransform<Parameter>, StackFunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String) = paramType.primitive.let { if ( it == "pointer" ) "long" else it }.let { paramType ->
		(0..size - 1).map { "$paramType $newName$it" }.reduce { a, b -> "$a, $b" }
	} // Replace with vector elements
	override fun transformCall(param: Parameter, original: String) = "memAddress(${param.name})" // Replace with stack buffer
	override fun setupStack(func: Function, qtype: Parameter, writer: PrintWriter) {
		writer.print("\t\t\t${paramType.box}Buffer ${qtype.name} = stack.${elementType}s(${newName}0")
		for (i in 1..(size - 1))
			writer.print(", $newName$i")
		writer.println(");")
	}
}

internal object MapPointerTransform : FunctionTransform<ReturnValue> {
	override fun transformDeclaration(param: ReturnValue, original: String) = "ByteBuffer" // Return a ByteBuffer
	override fun transformCall(param: ReturnValue, original: String) = """int $MAP_LENGTH = ${param[MapPointer].sizeExpression};
		return $MAP_OLD == null ? memByteBuffer($RESULT, $MAP_LENGTH) : memSetupBuffer($MAP_OLD, $RESULT, $MAP_LENGTH);"""
}

internal class MapPointerExplicitTransform(val lengthParam: String, val addParam: Boolean = true) : FunctionTransform<ReturnValue> {
	override fun transformDeclaration(param: ReturnValue, original: String) = "ByteBuffer" // Return a ByteBuffer
	override fun transformCall(param: ReturnValue, original: String) =
		"$MAP_OLD == null ? memByteBuffer($RESULT, (int)$lengthParam) : memSetupBuffer($MAP_OLD, $RESULT, (int)$lengthParam)"
}

internal val BufferLengthTransform: FunctionTransform<Parameter> = object : FunctionTransform<Parameter>, StackFunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String) = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String) = "memAddress(${param.name})" // Replace with stack buffer
	override fun setupStack(func: Function, qtype: Parameter, writer: PrintWriter) = writer.println("\t\t\tIntBuffer ${qtype.name} = stack.ints(0);")
}

internal class StringAutoSizeTransform(val autoSizeParam: Parameter) : FunctionTransform<Parameter>, CodeFunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String) = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String) = "memAddress(${param.name})" // Replace with address of allocated buffer
	override fun generate(qtype: Parameter, code: Code): Code {
		val len = "${if ( 4 < (autoSizeParam.nativeType.mapping as PrimitiveMapping).bytes ) "(int)" else ""}${autoSizeParam.name}"
		return code.append(
			javaBeforeNative = statement("\t\tByteBuffer ${qtype.name} = memAlloc($len);", ApplyTo.ALTERNATIVE),
			javaFinally = statement("\t\t\tmemFree(${qtype.name});")
		)
	}
}

internal class StringAutoSizeStackTransform(val autoSizeParam: Parameter) : FunctionTransform<Parameter>, StackFunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String) = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String) = "memAddress(${param.name})" // Replace with address of allocated buffer
	override fun setupStack(func: Function, qtype: Parameter, writer: PrintWriter) {
		val len = "${if ( 4 < (autoSizeParam.nativeType.mapping as PrimitiveMapping).bytes ) "(int)" else ""}${autoSizeParam.name}"
		writer.println("\t\t\tByteBuffer ${qtype.name} = stack.malloc($len);")
	}
}

internal val BufferReplaceReturnTransform: FunctionTransform<Parameter> = object : FunctionTransform<Parameter>, StackFunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String) = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String) = "memAddress(${param.name})" // Replace with stuck buffer
	override fun setupStack(func: Function, qtype: Parameter, writer: PrintWriter) {
		writer.println("\t\t\tPointerBuffer ${qtype.name} = stack.pointers(NULL);")
	}
}

internal class BufferAutoSizeReturnTransform(
	val outParam: Parameter,
	val lengthExpression: String,
	val encoding: String? = null
) : FunctionTransform<ReturnValue> {
	override fun transformDeclaration(param: ReturnValue, original: String) = (outParam.nativeType as PointerType).elementType!!.let {
		if ( it is StructType )
			"${it.javaMethodType}.Buffer"
		else
			"${it.javaMethodType}"
	}

	override fun transformCall(param: ReturnValue, original: String) = (outParam.nativeType as PointerType).elementType!!.let {
		"\t\treturn ${if (it is StructType)
			"${it.javaMethodType}.create"
		else
			"mem${it.javaMethodType}"
		}(${outParam.name}.get(0), $lengthExpression);"
	}
}

internal class BufferReturnTransform(
	val outParam: Parameter,
	val lengthParam: String,
	val encoding: String? = null
) : FunctionTransform<ReturnValue> {

	override fun transformDeclaration(param: ReturnValue, original: String) = if ( encoding == null) (outParam.nativeType.mapping as PointerMapping).javaMethodType.simpleName else "String"
	override fun transformCall(param: ReturnValue, original: String): String {
		return if ( encoding != null )
			"\t\treturn mem$encoding(${outParam.name}, $lengthParam.get(0));"
		else if ( outParam.nativeType.mapping !== PointerMapping.DATA_BYTE )
			"\t\t${outParam.name}.limit($lengthParam.get(0));\n" +
			"\t\treturn ${outParam.name}.slice();"
		else
			"\t\treturn memSlice(${outParam.name}, $lengthParam.get(0));"
	}
}

internal class BufferReturnNTTransform(
	val outParam: Parameter,
	val maxLengthParam: String,
	val encoding: String
) : FunctionTransform<ReturnValue> {
	override fun transformDeclaration(param: ReturnValue, original: String) = "String"
	override fun transformCall(param: ReturnValue, original: String): String =
		"\t\treturn mem$encoding(memByteBufferNT${(outParam.nativeType as CharSequenceType).charMapping.bytes}(memAddress(${outParam.name}), $maxLengthParam));"
}

internal open class PointerArrayTransform(val paramType: String) : FunctionTransform<Parameter>, StackFunctionTransform<Parameter>, CodeFunctionTransform<Parameter> {
	override fun transformDeclaration(param: Parameter, original: String): String? {
		val name = if ( paramType.isEmpty() ) param[PointerArray].singleName else param.name
		val paramClass = param[PointerArray].elementType.let {
			if ( it.mapping === PointerMapping.OPAQUE_POINTER )
				"long"
			else if ( it is CharSequenceType )
				"CharSequence"
			else
				"ByteBuffer"
		}
		return "$paramClass$paramType $name"
	}

	override fun transformCall(param: Parameter, original: String) = "${param.name}$POINTER_POSTFIX" // Replace with stuck buffer
	override fun setupStack(func: Function, qtype: Parameter, writer: PrintWriter) = writer.setupStackImpl(qtype)

	private fun PrintWriter.setupStackImpl(param: Parameter) {
		val pointerArray = param[PointerArray]
		if ( pointerArray.lengthsParam != null )
			return

		println((if ( paramType.isNotEmpty() ) param.name else pointerArray.singleName).let {
			"\t\t\tlong ${param.name}$POINTER_POSTFIX = org.lwjgl.system.APIUtil.apiArray${if ( pointerArray.elementType is CharSequenceType ) pointerArray.elementType.charMapping.charset else ""}(stack, $it);"
		})
	}

	override fun generate(qtype: Parameter, code: Code): Code {
		val pointerArray = qtype[PointerArray]
		val elementType = pointerArray.elementType

		if ( elementType is CharSequenceType ) {
			return code.append(javaAfterNative = statement(
				"\t\torg.lwjgl.system.APIUtil.apiArrayFree(${qtype.name}$POINTER_POSTFIX, ${if ( paramType.isEmpty() ) "1" else "${qtype.name}.length"});",
				ApplyTo.ALTERNATIVE
			))
		}

		return code
	}
}

internal object PointerArrayTransformSingle : PointerArrayTransform(""), SkipCheckFunctionTransform

internal val PointerArrayTransformArray = PointerArrayTransform("[]")
internal val PointerArrayTransformVararg = PointerArrayTransform("...")

internal class PointerArrayLengthsTransform(
	val arrayParam: Parameter,
	val multi: Boolean
) : FunctionTransform<Parameter>, StackFunctionTransform<Parameter>, SkipCheckFunctionTransform {
	override fun transformDeclaration(param: Parameter, original: String) = null // Remove the parameter
	override fun transformCall(param: Parameter, original: String) = // Replace with stack address - length(s) offset
		if ( multi )
			"${arrayParam.name}$POINTER_POSTFIX - (${arrayParam.name}.length << ${if ( param.nativeType.mapping == PointerMapping.DATA_INT ) "2" else "POINTER_SHIFT"})"
		else
			"${arrayParam.name}$POINTER_POSTFIX - ${if ( param.nativeType.mapping == PointerMapping.DATA_INT ) "4" else "POINTER_SIZE"}"

	override fun setupStack(func: Function, qtype: Parameter, writer: PrintWriter) = writer.setupStackImpl(qtype)

	private fun PrintWriter.setupStackImpl(param: Parameter) {
		val pointerArray = arrayParam[PointerArray]

		val lengthType = (param.nativeType.mapping as PointerMapping).primitive[0]
		println((if ( multi ) arrayParam.name else pointerArray.singleName).let {
			if ( pointerArray.elementType is CharSequenceType )
				"\t\t\tlong ${arrayParam.name}$POINTER_POSTFIX = org.lwjgl.system.APIUtil.apiArray${pointerArray.elementType.charMapping.charset}$lengthType(stack, $it);"
			else
				"\t\t\tlong ${arrayParam.name}$POINTER_POSTFIX = org.lwjgl.system.APIUtil.apiArray$lengthType(stack, $it);"
		})
	}
}