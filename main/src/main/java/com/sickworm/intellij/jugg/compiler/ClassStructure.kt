package com.sickworm.intellij.jugg.compiler

import com.googlecode.d2j.DexConstants
import com.googlecode.d2j.Field
import com.googlecode.d2j.Method
import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.node.DexFieldNode
import com.googlecode.d2j.node.DexMethodNode
import java.util.concurrent.ConcurrentHashMap

/**
 * A dex class structure parsed from .dex file.
 */
class ClassNode(
    val dexFileName: String,
    val className: String,
    val access: Int,
    val methods: List<MethodNode>,
    val fields: List<FieldNode>,
    val interfaceNames: List<String>,
    val superClass: String,
    sourceArg: String?,
) {

    val source: String = sourceArg ?: NO_SOURCE

    val isAbstract: Boolean get() = access and DexConstants.ACC_ABSTRACT != 0

    constructor(dexFileName: String, node: DexClassNode): this(
        dexFileName = dexFileName,
        className = node.className,
        access = node.access,
        methods = node.methods?.map { MethodNode(it) }?: emptyList(),
        fields = node.fields?.map { FieldNode(it) }?: emptyList(),
        interfaceNames = node.interfaceNames?.map { ClassStringPool[it] }?: emptyList(),
        superClass = ClassStringPool[node.superClass],
        sourceArg = node.source,
    )

    companion object {
        const val JUGG_DEPLOYED_DEX_FILE_NAME = "jugg_deployed.dex" // virtual dex file name, not really exists
        const val NO_SOURCE = "no_source"

        // e.g. Landroid/support/v4/os/ResultReceiver$1;
        // ->
        // android.support.v4.os.ResultReceiver$1
        private fun String.convertSigFormatToPackage(): String {
            return this.convertSigFormatToNormal().replace('/', '.')
        }

        // e.g. Landroid/support/v4/os/ResultReceiver$1;
        // ->
        // android/support/v4/os/ResultReceiver$1
        private fun String.convertSigFormatToNormal(): String {
            return this.substring(1, this.length - 1)
        }
    }

}

/**
 * A dex method structure parsed from .dex file.
 */
class MethodNode(
    owner: String,
    access: Int,
    name: String,
    desc: String,
) {

    val isAbstract: Boolean get() = access and DexConstants.ACC_ABSTRACT != 0

    constructor(node: DexMethodNode): this(
        owner = node.method.owner,
        access = node.access,
        name = node.method.name,
        desc = node.method.desc
    )

    constructor(method: Method): this(
        owner = method.owner,
        access = MISS_ACCESS,
        name = method.name,
        desc = method.desc
    )

    val owner = ClassStringPool[owner]
    @Suppress("CanBePrimaryConstructorProperty")
    val access = access
    val name = ClassStringPool[name]
    val desc = ClassStringPool[desc]

    private val signature get() = "$access ${owner}.${name}${desc}"

    fun isEffectedChanged(method: MethodNode): Boolean {
        val accessWithoutAbstract = access and DexConstants.ACC_ABSTRACT.inv() and DexConstants.ACC_PRIVATE.inv()
        val otherAccessWithoutAbstract = method.access and DexConstants.ACC_ABSTRACT.inv() and DexConstants.ACC_PRIVATE.inv()
        return this.owner == method.owner
                && accessWithoutAbstract == otherAccessWithoutAbstract
                && this.name == method.name
                && this.desc == method.desc
    }

    override fun equals(other: Any?): Boolean {
        if (other !is MethodNode) {
            return false
        }
        if (other.owner != owner) {
            return false
        }
        if (other.access != access) {
            return false
        }
        if (other.name != name) {
            return false
        }
        if (other.desc != desc) {
            return false
        }
        return true
    }

    fun equalsWithoutAccess(other: MethodNode): Boolean {
        return this.owner == other.owner
                && this.name == other.name
                && this.desc == other.desc
    }

    override fun toString(): String {
        return signature
    }

    fun toStringWithoutOwner(): String {
        return "$access $name$desc"
    }

    override fun hashCode(): Int {
        return owner.hashCode() + access + name.hashCode() + desc.hashCode()
    }

    companion object {
        const val MISS_ACCESS = -1
    }
}

/**
 * A dex field structure parsed from .dex file.
 */
class FieldNode(owner: String, access: Int, name: String, type: String) {

    constructor(node: DexFieldNode): this(
        owner = node.field.owner,
        access = node.access,
        name = node.field.name,
        type = node.field.type
    )

    constructor(field: Field): this(
        owner = field.owner,
        access = MISS_ACCESS,
        name = field.name,
        type = field.type
    )

    val owner = ClassStringPool[owner]
    @Suppress("CanBePrimaryConstructorProperty")
    val access = access
    val name = ClassStringPool[name]
    val type = ClassStringPool[type]

    private val signature get() = "$access $type $owner.$name"

    override fun equals(other: Any?): Boolean {
        if (other !is FieldNode) {
            return false
        }
        if (other.owner != owner) {
            return false
        }
        if (other.access != access) {
            return false
        }
        if (other.name != name) {
            return false
        }
        if (other.type != type) {
            return false
        }
        return true
    }

    fun equalsWithoutAccess(other: FieldNode): Boolean {
        return this.owner == other.owner
                && this.name == other.name
                && this.type == other.type
    }

    override fun toString(): String {
        return signature
    }

    fun toStringWithoutOwner(): String {
        return "$access $type $name"
    }

    override fun hashCode(): Int {
        return owner.hashCode() + access + name.hashCode() + type.hashCode()
    }

    companion object {
        const val MISS_ACCESS = -1
    }
}

/** For save memory for same string but different instance */
object ClassStringPool {

    private var stringPool = ConcurrentHashMap<String, String>()

    operator fun get(string: String): String {
        val cacheString = stringPool[string]
        if (cacheString != null) {
            return cacheString
        }
        stringPool[string] = string
        return string
    }

    fun clear() {
        stringPool = ConcurrentHashMap()
        // Removed explicit System.gc() — it caused a Full STW pause (~300ms) that froze the IDE
        // UI thread after every parseDex call. Let the JVM decide when to collect.
    }
}