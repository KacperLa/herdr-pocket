package dev.kaspy.herdr

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Registry holds only opaque identifiers; each host's settings have their own bucket. */
internal data class SshHostProfiles(val ids:List<String>,val selected:String?) {
    fun select(id:String)=takeIf {id in ids}?.copy(selected=id)
    fun add(id:String)=takeIf {validId(id) && id !in ids && ids.size<MAX_HOSTS}
        ?.let {SshHostProfiles(ids+id,selected)}
    fun remove(id:String)=takeIf {id in ids}?.let {
        val remaining=ids-id
        SshHostProfiles(remaining,selected?.takeUnless {it==id})
    }
    fun encode()=JSONObject().put("ids",JSONArray(ids)).put("selected",selected ?: JSONObject.NULL).toString()

    companion object {
        const val LEGACY="legacy"
        const val MAX_HOSTS=40
        fun validId(id:String)=id==LEGACY || runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false)
        fun decode(value:String?):SshHostProfiles {
            if(value==null) return SshHostProfiles(listOf(LEGACY),LEGACY)
            return runCatching {
                val obj=JSONObject(value)
                val array=obj.getJSONArray("ids")
                require(array.length()<=MAX_HOSTS)
                val ids=(0 until array.length()).map {array.getString(it)}
                require(ids.size==ids.toSet().size && ids.all(::validId))
                val selected=if(obj.isNull("selected")) null else obj.getString("selected")
                require(selected==null || selected in ids)
                SshHostProfiles(ids,selected)
            }.getOrElse {SshHostProfiles(emptyList(),null)}
        }

        fun preferenceBucket(id:String?)=when(id) {
            LEGACY -> "workspace"
            null -> "ssh_host_unselected"
            else -> { require(validId(id)); "ssh_host_$id" }
        }
        fun vaultSuffix(id:String?)=when(id) {
            LEGACY -> ""
            null -> ".unselected"
            else -> { require(validId(id)); ".$id" }
        }

        private val settings=setOf("host","port","user","key_document_uri","pin","agent","command","tui_session","connection_label")
        fun isLegacyHostKey(key:String)=key in settings || key.startsWith("startup_") ||
            key.startsWith("host-pin:") || key.startsWith("agent_pins_v1_") || key.startsWith("recent-session:")
    }
}

internal data class SavedSshHost(val id:String,val name:String,val address:String,val configured:Boolean)

internal data class SshHostEditorFields(val name:String="",val host:String="",val port:String="22",val user:String="")
