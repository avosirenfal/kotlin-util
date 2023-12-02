/**
 * prettyprinkter.kt (c) by github.com/avosirenfal
 *
 * prettyprinter.kt is licensed under a
 * Creative Commons Attribution-ShareAlike 4.0 International License.
 *
 * You should have received a copy of the license along with this
 * work. If not, see <http://creativecommons.org/licenses/by-sa/4.0/>.
 */
@file:Suppress("unused")

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import kotlin.math.pow
import kotlin.math.sqrt

interface PrettyPrintable {
	fun pformat(printer: PrettyPrinter): String
}

interface SimplePrintable {
	fun pformat(): String
}

interface PrettyPrinter_NameOverride {
	val pprint_name: String
}

fun repr(obj: Any?): String {
	if(obj == null)
		return "null"

	if(obj is String) {
		val s = obj
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\r", "\\r")
			.replace("\n", "\\n")
		return "\"$s\""
	}

	return obj.toString()
}

fun is_primitive(obj: Any?): Boolean {
//	contract {
//		returns(false) implies (obj != null)
//	}
	return obj == null ||
			obj is Byte || obj is Short || obj is Int || obj is Long ||
			obj is Float || obj is Double ||
			obj is Char || obj is String
}

fun indent_lines(s: String, indent: String, include_first: Boolean=false, with_lines: Boolean=false): String {
	val lines = s.split("\n")

	if(!with_lines || lines.size <= 2)
		return lines.joinToString("\n$indent", prefix=if(include_first) indent else "")

	val ret = lines.subList(0, lines.size-1).joinToString("\n$indent|", prefix=if(include_first) indent else "")

	return "$ret\n$indent${lines[lines.size-1]}"
}

fun standard_deviation(numArray: DoubleArray): Double {
	val mean = numArray.average()
	val sd = numArray.fold(0.0) { accumulator, next -> accumulator + (next - mean).pow(2.0) }
	return sqrt(sd / numArray.size)
}

open class PrettyPrinter(
	var INDENT_STR: String="    ",
	var SKIP_NULL: Boolean=false,
	var WITH_TYPES: Boolean=false,
	var WITH_IDS: Boolean=false,
	var WITH_LINES: Boolean=true,
	var COLUMN_WRAP: Int=100,
	var ITERABLES_MULTIPLE_PER_LINE: Boolean=true,
	var MAX_ITERABLE_COUNT: Int=50,
	var SKIP_INNER_CLASSES: Boolean=true,
) {
	var seen: MutableSet<Any> = mutableSetOf()

	fun pprint(obj: Any?) {
		println(this.pformat(obj))
	}

	fun pformat(obj: Any?): String {
		if(obj == null || is_primitive(obj))
			return repr(obj)

		seen.clear()
		try {
			return this.pformat_recursive(obj)
		} finally {
			seen.clear()
		}
	}

	protected fun pformat_map(map: Map<*, *>): String {
		val items = map.map { (key, value) -> "${pformat_recursive(key)}: ${pformat_recursive(value)}" }
		val check = items.joinToString(", ")

		if(check.length <= COLUMN_WRAP)
			return "{$check}"

		return "{\n$INDENT_STR" + items.joinToString(",\n$INDENT_STR") + "\n}"
	}

	protected fun pformat_iterable(iter: Iterable<*>, open: Char='[', close: Char=']'): String {
		val lines = ArrayList<String>()
		val sb = StringBuilder()
		val sizes = ArrayList<Double>()
		var count = 0

		for(item in iter) {
			val text = this.pformat_recursive(item)

			sizes.add(text.length.toDouble())

			// this is bigger than a full line and we can't wrap it
			if(text.length > COLUMN_WRAP || !ITERABLES_MULTIPLE_PER_LINE) {
				if(sb.isNotEmpty()) {
					sb.append(",")
					lines.add(sb.toString())
					sb.clear()
				}
			} else if(sb.isNotEmpty() && text.length + sb.length > COLUMN_WRAP) {
				sb.append(",")
				lines.add(sb.toString())
				sb.clear()
			}

			if(sb.isNotEmpty())
				sb.append(", ")

			if(count >= MAX_ITERABLE_COUNT) {
				sb.append("...")
				break
			} else {
				sb.append(text)
				count += 1
			}
		}

		if(sb.isNotEmpty())
			lines.add(sb.toString())

		if(count == 0)
			return "$open$close"

		// compact style is used if all values are similar in length and small enough
		return if(sizes.size <= 1 || (sizes.all {it <= 10} && standard_deviation(sizes.toDoubleArray()) < 5.0))
			open + lines.joinToString("\n") + close
		else
			"$open\n" + lines.joinToString("\n") { indent_lines(it, INDENT_STR, true, with_lines = WITH_LINES) } + "\n$close"
	}

	protected fun pformat_list(list: List<*>): String {
		return this.pformat_iterable(list, '[', ']')
	}

	protected fun pformat_set(set: Set<*>): String {
		return this.pformat_iterable(set, '{', '}')
	}

	protected fun pformat_recursive(obj: Any?): String {
		if(obj == null || is_primitive(obj))
			return repr(obj)
		else if(obj.javaClass.isSynthetic)
			return ""
		else if(obj is Enum<*>)
			return obj.toString()
		else if(obj is PrettyPrintable)
			return obj.pformat(this)
		else if(obj is SimplePrintable)
			return obj.pformat()
		else if(obj is Map<*, *>)
			return this.pformat_map(obj)
		else if(obj is List<*>)
			return this.pformat_list(obj)
		else if(obj is Set<*>)
			return this.pformat_set(obj)
		else if(obj is Array<*>) // || obj is Iterable<*>
			return this.pformat_iterable(obj.asIterable())
		else if(obj is Boolean)
			return if(obj) "True" else "False"
		else if(obj is UInt)
			return obj.toString()

		fun reflect(obj: Any, check: (Any) -> Boolean, default: Boolean=false): Boolean {
			try {
				return check(obj)
			}
			catch(_: KotlinReflectionNotSupportedError) {}
			catch(_: UnsupportedOperationException) {}

			return default
		}

		if(obj is kotlin.jvm.internal.Lambda<*>)//reflect(obj, {it::class.qualifiedName == null}))
			return obj.toString()

		val simplename = !WITH_IDS || reflect(obj, {it::class.objectInstance != null}, false)
		var classname =  if(obj is PrettyPrinter_NameOverride) obj.pprint_name else obj.javaClass.simpleName

		// System.identityHashCode(obj) used here because we are trying to check object identity, not
		// object equality
		if(!simplename)
			classname = "${obj.javaClass.simpleName}#${Integer.toHexString(System.identityHashCode(obj))}"

		if(!seen.add(System.identityHashCode(obj)))
			return if(simplename)
				classname
			else
				"<$classname (seen)>"

		val fields: ArrayList<String> = ArrayList()

		for(field in obj.javaClass.declaredFields) {
			if(field.isSynthetic)
				continue

			if(!field.trySetAccessible())
				continue

			val v = field.get(obj)
			if(SKIP_NULL && v == null)
				continue

			if(v != null && v::class.isCompanion)
				continue

			if(SKIP_INNER_CLASSES && v != null &&
				(
					(v::class.isInner) ||
					(v::class.java.isMemberClass && !Modifier.isStatic(v::class.java.modifiers)) ||
					// kotlinx.serialization compiler generated serializers start with $
					(field.name.startsWith("$"))
				)
			)
				continue
			val left: String = if(WITH_TYPES)
				"${field.name}: ${getDeclaration(field)}="
			else
				"${field.name}="

			fields.add("$left${this.pformat_recursive(v)}")
		}

		return if(fields.size < 1 || reflect(obj, {it::class.objectInstance != null})) {
			classname
		} else if(fields.size <= 1 || classname.length+3+ fields.sumOf { it.length } +(2*fields.size) <= COLUMN_WRAP) {
			"$classname(${fields.joinToString(", ")})"
		} else {
			"$classname(\n" +
					fields.joinToString(",\n") { indent_lines(it, INDENT_STR, true, with_lines = WITH_LINES) } + "\n)"
		}
	}
}

fun getDeclaration(field: Field): String {
	return getDeclarationForType(field.genericType)
}

// https://stackoverflow.com/a/45085377/1316748
private fun getDeclarationForType(genericType: Type): String {
	when (genericType) {
		is ParameterizedType -> {
			// types with parameters
			var declaration = genericType.rawType.typeName
			declaration += "<"

			val typeArgs = genericType.actualTypeArguments

			for (i in typeArgs.indices) {
				val typeArg = typeArgs[i]

				if (i > 0) {
					declaration += ", "
				}

				// note: recursive call
				declaration += getDeclarationForType(typeArg)
			}

			declaration += ">"
			declaration = declaration.replace('$', '.')
			return declaration
		}
		is Class<*> -> {
			return if (genericType.isArray) {
				// arrays
				genericType.componentType.canonicalName + "[]"
			} else {
				// primitive and types without parameters (normal/standard types)
				genericType.canonicalName
			}
		}
		else -> // e.g. WildcardTypeImpl (Class<? extends Integer>)
			return genericType.typeName
	}
}
