#!/usr/bin/env python3
"""Real-JDK smoke test: JAVA_HOME=<managed EE home> python3 test-trust-store.py."""
import importlib.util
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
HOME = Path(os.environ["JAVA_HOME"])
SUFFIX = ".exe" if os.name == "nt" else ""
KEYTOOL = HOME / "bin" / ("keytool" + SUFFIX)
ROOTS = {"emsigneccrootcag3 [jdk]", "comodoaaaca [jdk]"}
spec = importlib.util.spec_from_file_location("build_ee", HERE / "build-ee.py")
build_ee = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_ee)


def certificates(path):
    output = subprocess.run([str(KEYTOOL), "-J-Duser.language=en", "-J-Duser.country=US",
                             "-list", "-rfc", "-keystore", str(path), "-storepass", "changeit"],
                            check=True, capture_output=True, text=True).stdout
    entries = {}
    for entry in output.split("Alias name: ")[1:]:
        alias = entry.splitlines()[0]
        entries[alias] = re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----",
                                    entry, re.S)
        if not entries[alias]:
            raise AssertionError(f"no certificate for {alias}")
    if not entries:
        raise AssertionError("empty certificate inventory")
    return entries


class TrustStoreTest(unittest.TestCase):
    def test_real_store(self):
        with tempfile.TemporaryDirectory(prefix="EE trust smoke ") as work:
            home = Path(work) / "Contents" / "Home"
            store = home / "lib" / "security" / "cacerts"
            store.parent.mkdir(parents=True)
            # Use the real JDK executable and its modules without copying the entire JDK.
            (home / "bin").symlink_to(HOME / "bin", target_is_directory=True)
            shutil.copy2(HOME / "lib" / "security" / "cacerts", store)
            before = certificates(store)
            self.assertTrue(ROOTS.issubset(before), "fixture must contain both distrusted roots")
            mode = store.stat().st_mode
            build_ee.harden_trust_store(str(HERE.parent.parent), str(home))
            after = certificates(store)
            self.assertEqual(after, {k: v for k, v in before.items() if k not in ROOTS})
            self.assertEqual(mode, store.stat().st_mode)
            hardened = store.read_bytes()
            build_ee.harden_trust_store(str(HERE.parent.parent), str(home))
            self.assertEqual(hardened, store.read_bytes())
            print(f"preserved {len(after)} certificates; removed exactly {sorted(ROOTS)}; idempotent")

            def keytool(*args):
                subprocess.run([str(KEYTOOL), *args, "-keystore", str(store),
                                "-storepass", "changeit"], check=True, capture_output=True)

            shutil.copy2(HOME / "lib" / "security" / "cacerts", store)
            pem = home / "certificate.pem"
            pem.write_text(before["emsigneccrootcag3 [jdk]"][0])
            keytool("-importcert", "-noprompt", "-alias", "duplicate-root", "-file", str(pem))
            keytool("-changealias", "-alias", "comodoaaaca [jdk]", "-destalias", "renamed-root")
            build_ee.harden_trust_store(str(HERE.parent.parent), str(home))
            self.assertEqual(after, certificates(store))
            unrelated = next(iter(after))
            pem.write_text(after[unrelated][0])
            keytool("-importcert", "-noprompt", "-alias", "comodoaaaca [jdk]", "-file", str(pem))
            mismatched = store.read_bytes()
            with self.assertRaises(subprocess.CalledProcessError):
                build_ee.harden_trust_store(str(HERE.parent.parent), str(home))
            self.assertEqual(mismatched, store.read_bytes())
            store.write_bytes(hardened)
            pem.unlink()
            print("renamed/duplicate roots removed; mismatched alias rejected without mutation")
            if os.name != "nt" and os.geteuid() != 0:
                store.chmod(0)
                try:
                    with self.assertRaises(subprocess.CalledProcessError):
                        build_ee.harden_trust_store(str(HERE.parent.parent), str(home))
                finally:
                    store.chmod(mode)

            password_store = store.with_name("password-cacerts")
            subprocess.run([str(KEYTOOL), "-importkeystore", "-srckeystore", str(store),
                            "-srcstorepass", "changeit", "-destkeystore", str(password_store),
                            "-deststoretype", "JKS", "-deststorepass", "different-password", "-noprompt"],
                           check=True, capture_output=True)
            password_store.replace(store)
            protected = store.read_bytes()
            with self.assertRaises(subprocess.CalledProcessError):
                build_ee.harden_trust_store(str(HERE.parent.parent), str(home))
            self.assertEqual(protected, store.read_bytes())
            store.write_bytes(b"corrupt store")
            with self.assertRaises(subprocess.CalledProcessError):
                build_ee.harden_trust_store(str(HERE.parent.parent), str(home))
            self.assertEqual(b"corrupt store", store.read_bytes())
            store.unlink()
            with self.assertRaises(subprocess.CalledProcessError):
                build_ee.harden_trust_store(str(HERE.parent.parent), str(home))
            (home / "bin").unlink()
            with self.assertRaises(FileNotFoundError):
                build_ee.harden_trust_store(str(HERE.parent.parent), str(home))
            self.assertEqual([], list(store.parent.iterdir()))
            print("wrong-password, corrupt, missing-store and missing-tool failures propagated")


if __name__ == "__main__":
    unittest.main()
