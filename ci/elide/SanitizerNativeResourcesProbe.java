import org.graalvm.polyglot.Context;

class SanitizerNativeResourcesProbe {
    public static void main(String[] args) {
        try (Context context = Context.newBuilder("python").allowAllAccess(true)
                        .option("engine.WarnInterpreterOnly", "false")
                        .option("python.PosixModuleBackend", "native")
                        .option("python.CompressionModulesBackend", "native").build()) {
            context.eval("python", """
                            import bz2, lzma, zlib, os, ctypes, sqlite3
                            data = b'sanitized GraalPy native resource' * 100
                            for codec in (bz2, lzma, zlib):
                                assert codec.decompress(codec.compress(data)) == data
                            assert os.getpid() > 0
                            libc = ctypes.CDLL(None)
                            libc.strlen.argtypes = [ctypes.c_char_p]
                            libc.strlen.restype = ctypes.c_size_t
                            assert libc.strlen(b'native') == 6
                            with sqlite3.connect(':memory:') as db:
                                assert db.execute('select 42').fetchone()[0] == 42
                            print('Packaged Truffle/NFI/GraalPy native resources passed')
                            """);
        }
    }
}
