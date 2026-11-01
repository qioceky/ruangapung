package id.ruangapung;

// Binder yang berjalan di Shizuku user-service (proses terpisah, UID shell).
// Dipanggil dari proses aplikasi — JANGAN panggil dari main thread.
interface IPrivilegedService {
    // Siapkan InputManager via refleksi. Wajib dipanggil sekali setelah bind.
    boolean init();

    // Jalankan perintah shell ("sh -c"). Mengembalikan exit code (0 = sukses).
    int execShell(String cmd);

    // Injeksi sentuhan ke display virtual (fire-and-forget).
    oneway void injectTouch(int action, in float[] xs, in float[] ys, int displayId);

    // Injeksi tombol (DOWN+UP) ke display virtual (fire-and-forget).
    oneway void injectKey(int keyCode, int displayId);
}
