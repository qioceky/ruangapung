package id.ruangapung

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

/**
 * Binder manual (tanpa AIDL) untuk PrivilegedService yang berjalan di
 * Shizuku user-service (proses terpisah, UID shell).
 *
 * Dipilih manual karena toolchain AIDL tidak terpicu di build CI proyek ini.
 * injectTouch/injectKey memakai FLAG_ONEWAY (fire-and-forget) agar tidak
 * memblokir thread pengirim saat sentuhan mengalir deras.
 */
interface IPrivilegedService : IInterface {

    /** Siapkan InputManager via refleksi. Wajib dipanggil sekali setelah bind. */
    fun init(): Boolean

    /** Jalankan perintah shell ("sh -c"). Mengembalikan exit code (0 = sukses). */
    fun execShell(cmd: String): Int

    /** Injeksi sentuhan ke display virtual. */
    fun injectTouch(action: Int, xs: FloatArray, ys: FloatArray, displayId: Int)

    /** Injeksi tombol (DOWN+UP) ke display virtual. */
    fun injectKey(keyCode: Int, displayId: Int)

    abstract class Stub : Binder(), IPrivilegedService {

        companion object {
            const val DESCRIPTOR = "id.ruangapung.IPrivilegedService"
            const val TRANSACTION_init = IBinder.FIRST_CALL_TRANSACTION + 0
            const val TRANSACTION_execShell = IBinder.FIRST_CALL_TRANSACTION + 1
            const val TRANSACTION_injectTouch = IBinder.FIRST_CALL_TRANSACTION + 2
            const val TRANSACTION_injectKey = IBinder.FIRST_CALL_TRANSACTION + 3

            fun asInterface(binder: IBinder?): IPrivilegedService? {
                if (binder == null) return null
                val local = binder.queryLocalInterface(DESCRIPTOR)
                if (local is IPrivilegedService) return local
                return Proxy(binder)
            }
        }

        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    return true
                }
                TRANSACTION_init -> {
                    data.enforceInterface(DESCRIPTOR)
                    val result = init()
                    reply?.writeNoException()
                    reply?.writeInt(if (result) 1 else 0)
                    return true
                }
                TRANSACTION_execShell -> {
                    data.enforceInterface(DESCRIPTOR)
                    val result = execShell(data.readString() ?: "")
                    reply?.writeNoException()
                    reply?.writeInt(result)
                    return true
                }
                TRANSACTION_injectTouch -> {
                    data.enforceInterface(DESCRIPTOR)
                    val action = data.readInt()
                    val xs = data.createFloatArray() ?: FloatArray(0)
                    val ys = data.createFloatArray() ?: FloatArray(0)
                    val displayId = data.readInt()
                    injectTouch(action, xs, ys, displayId)
                    return true
                }
                TRANSACTION_injectKey -> {
                    data.enforceInterface(DESCRIPTOR)
                    val keyCode = data.readInt()
                    val displayId = data.readInt()
                    injectKey(keyCode, displayId)
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }

        private class Proxy(private val remote: IBinder) : IPrivilegedService {
            override fun asBinder(): IBinder = remote

            override fun init(): Boolean {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    remote.transact(TRANSACTION_init, data, reply, 0)
                    reply.readException()
                    reply.readInt() != 0
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }

            override fun execShell(cmd: String): Int {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(cmd)
                    remote.transact(TRANSACTION_execShell, data, reply, 0)
                    reply.readException()
                    reply.readInt()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }

            override fun injectTouch(action: Int, xs: FloatArray, ys: FloatArray, displayId: Int) {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeInt(action)
                    data.writeFloatArray(xs)
                    data.writeFloatArray(ys)
                    data.writeInt(displayId)
                    remote.transact(TRANSACTION_injectTouch, data, null, IBinder.FLAG_ONEWAY)
                } finally {
                    data.recycle()
                }
            }

            override fun injectKey(keyCode: Int, displayId: Int) {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeInt(keyCode)
                    data.writeInt(displayId)
                    remote.transact(TRANSACTION_injectKey, data, null, IBinder.FLAG_ONEWAY)
                } finally {
                    data.recycle()
                }
            }
        }
    }
}
