#!/usr/bin/env python3
# -*- coding: utf-8 -*
import argparse
import configparser
import io
import os
import re
import subprocess
import sys

try:
    import pytest
except:  # noqa
    pass


INVALID_VALUES = {
    "vnd.myandroid.bortappid",
    "vnd.myandroid.bort.otaappid",
    "vnd.myandroid.bortfeaturename",
}


MAPPING = {
    # source file placeholder / preprocessor define name => bort.properties variable (and optional fallback)
    # Note: the leading $ is removed for preprocessor define names.
    "$BORT_APPLICATION_ID": ("BORT_APPLICATION_ID",),
    "$BORT_OTA_APPLICATION_ID": ("BORT_OTA_APPLICATION_ID",),
    "$BORT_FEATURE_NAME": ("BORT_FEATURE_NAME", "BORT_APPLICATION_ID"),
}


class JavaProperties:
    # The .properties file format is described here:
    # https://docs.oracle.com/javase/7/docs/api/java/util/Properties.html#load(java.io.Reader)
    # Use python's ConfigParser to parse it, it will get us most of the way there.

    def __init__(self, file):
        def _reader():
            yield "[main]"
            # yield from is only supported since python 3.3
            for line in file:
                yield line

        self.cp = configparser.ConfigParser(comment_prefixes=("#", "!"))
        self.cp.read_file(_reader())

    def get(self, key, **kwargs):
        return self.cp.get("main", key.lower(), **kwargs)

    @staticmethod
    def from_string(src):
        return JavaProperties(io.StringIO(src))


class Replacement:
    def __init__(self, prop_name, value):
        self.prop_name = prop_name
        self.value = value

    def __eq__(self, other):
        return (
            self.__class__ == other.__class__
            and self.prop_name == other.prop_name
            and self.value == other.value
        )


def _replace_placeholders(content, replacements):
    for placeholder, replacement in replacements.items():
        content = content.replace(placeholder, replacement.value)

    return content


def _write_if_changed(content, output_file_abspath):
    try:
        with open(output_file_abspath) as file:
            existing_content = file.read()
    except OSError:
        existing_content = None

    if content == existing_content:
        return  # Don't touch it to avoid rebuilds

    with open(output_file_abspath, "w") as file:
        file.write(content)


def _get_replacements(mapping, bort_props):
    def _find_first_replacement(prop_names):
        for prop in prop_names:
            value = bort_props.get(prop, fallback=None)
            if value:
                return Replacement(prop, value)
        return None

    replacements = {
        placeholder: _find_first_replacement(prop_names)
        for placeholder, prop_names in mapping.items()
    }

    for placeholder, replacement in replacements.items():
        if not replacement:
            variables = " or ".join(mapping[placeholder])
            raise Exception(
                "Missing value for {}. Please define {} in bort.properties!".format(
                    placeholder, variables
                )
            )
        if replacement.value in INVALID_VALUES:
            raise Exception(
                "Invalid value '{}' for '{}'. Please change in bort.properties!".format(
                    replacement.value, replacement.prop_name
                )
            )
    return replacements


def _generate_cpp_header(replacements):
    content = """// DO NOT EDIT -- GENERATED BY bort_src_gen.py
"""
    for placeholder in sorted(replacements.keys()):
        define_name = re.sub(r"^\$+", "", placeholder)
        define_value = replacements[placeholder].value
        content += "#define {} {}\n".format(define_name, define_value)
    return content


def _cmd_template(*, input_file, output_file, bort_properties_file):
    with open(bort_properties_file) as f:
        replacements = _get_replacements(MAPPING, JavaProperties(f))
    with open(input_file) as file:
        content = _replace_placeholders(file.read(), replacements)
    _write_if_changed(content, output_file)


def _cmd_cpp_header(*, output_file, bort_properties_file):
    with open(bort_properties_file) as f:
        content = _generate_cpp_header(_get_replacements(MAPPING, JavaProperties(f)))
    _write_if_changed(content, output_file)


def _parse_keytool_printcert_sha256(keytool_output, keytool_cmd):
    in_fingerprints_section = False
    for line in keytool_output.splitlines():
        if "Certificate fingerprints:" in line:
            in_fingerprints_section = True
        elif in_fingerprints_section and not line.startswith("\t"):
            in_fingerprints_section = False
        if in_fingerprints_section and "SHA256: " in line:
            return line.partition(": ")[2]
    else:
        cmd_str = " ".join(keytool_cmd)
        raise Exception(
            "Failed to extract SHA256 fingerprint. keytool cmd `{}` output:\n{}".format(
                cmd_str, keytool_output
            )
        )


def _hex_colon_format(hex_input):
    assert len(hex_input) % 2 == 0
    return ":".join([hex_input[idx : idx + 2].upper() for idx in range(0, len(hex_input), 2)])


def _parse_apksigner_cert_sha256(apksigner_output):
    return [
        _hex_colon_format(line.partition("certificate SHA-256 digest: ")[2])
        for line in apksigner_output.splitlines()
        if "certificate SHA-256 digest: " in line
    ]


def _get_java_bin_path(prog):
    java_home = os.environ.get("JAVA_HOME")
    return os.path.join(java_home, "bin", prog) if java_home else prog


def _run_keytool(*args):
    keytool_path = _get_java_bin_path("keytool")
    cmd = [keytool_path] + list(args)
    return subprocess.check_output(cmd).decode("utf8", errors="ignore"), cmd


def _get_apksigner_jar_path():
    bort_root = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
    return os.path.join(bort_root, "external", "apksigner", "apksigner.jar")


def _run_apksigner(*args):
    java_path = _get_java_bin_path("java")
    return subprocess.check_output(
        [java_path, "-jar", _get_apksigner_jar_path()] + list(args)
    ).decode("utf8", errors="ignore")


def fail(msg):
    sys.exit(msg)


class SignatureException(Exception):
    pass


def _check_signatures(*, output_file, apk_file, pem_file, apk_cert_sha256s, pem_sha256):
    if pem_sha256 not in apk_cert_sha256s:
        raise SignatureException(
            """MemfaultBort.apk signature does not match MemfaultBort.x509.pem certificate file!
  {} is signed with certificate(s) having the following SHA256 fingerprints:
    {}
  {} contains a certificate having the following SHA256 fingerprint:
    {}""".format(
                apk_file, apk_cert_sha256s, pem_file, pem_sha256
            )
        )
    with open(output_file, "w") as f:
        f.write("OK: {}".format(pem_sha256))


def _cmd_check_signature(*, output_file, apk_file, pem_file):
    try:
        apk_cert_sha256s = _parse_apksigner_cert_sha256(
            _run_apksigner("verify", "--print-certs", apk_file)
        )
    except Exception as e:
        raise Exception("Failed to extract certificates from {}".format(apk_file)) from e

    try:
        output, cmd = _run_keytool("-printcert", "-file", pem_file)
        pem_sha256 = _parse_keytool_printcert_sha256(output, cmd)
    except Exception as e:
        raise Exception("Failed to extract certificate from {}".format(pem_file)) from e

    try:
        _check_signatures(
            output_file=output_file,
            apk_file=apk_file,
            pem_file=pem_file,
            apk_cert_sha256s=apk_cert_sha256s,
            pem_sha256=pem_sha256,
        )
    except SignatureException as e:
        fail(str(e))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers()

    template_parser = subparsers.add_parser("template")
    template_parser.add_argument("input_file")
    template_parser.add_argument("output_file")
    template_parser.add_argument("bort_properties_file")
    template_parser.set_defaults(command=_cmd_template)

    cpp_header_parser = subparsers.add_parser("cpp-header")
    cpp_header_parser.add_argument("output_file")
    cpp_header_parser.add_argument("bort_properties_file")
    cpp_header_parser.set_defaults(command=_cmd_cpp_header)

    check_signature_parse = subparsers.add_parser("check-signature")
    check_signature_parse.add_argument("output_file")
    check_signature_parse.add_argument("apk_file")
    check_signature_parse.add_argument("pem_file")
    check_signature_parse.set_defaults(command=_cmd_check_signature)

    args = vars(parser.parse_args())
    command = args.pop("command", None)

    if not command:
        parser.print_help()
        sys.exit(1)

    command(**args)


def test_replace_placeholders():
    assert (
        _replace_placeholders(
            '<permission name="$PERM"/>', {"$PERM": Replacement("BORT_PERM", "com.myperm")}
        )
        == '<permission name="com.myperm"/>'
    )


def test_generate_cpp_header():
    assert (
        _generate_cpp_header(
            {
                "$PERM": Replacement("BORT_PERM", "com.myperm"),
                "$APP_ID": Replacement("BORT_ID", "com.app"),
            }
        )
        == """// DO NOT EDIT -- GENERATED BY bort_src_gen.py
#define APP_ID com.app
#define PERM com.myperm
"""
    )


def test_get_replacements():
    assert _get_replacements(
        {"REPL": ("VAR1", "VAR2")}, JavaProperties.from_string("VAR2=FOO")
    ) == {"REPL": Replacement("VAR2", "FOO")}

    with pytest.raises(
        Exception,
        match="Missing value for REPL. Please define VAR1 or VAR2 in bort.properties!",
    ):
        assert _get_replacements({"REPL": ("VAR1", "VAR2")}, JavaProperties.from_string("VAR3=FOO"))

    with pytest.raises(
        Exception,
        match="Invalid value 'vnd.myandroid.bortappid' for 'VAR1'. Please change in bort.properties!",
    ):
        assert _get_replacements(
            {"REPL": ("VAR1", "VAR2")}, JavaProperties.from_string("VAR1=vnd.myandroid.bortappid")
        )


def test_java_properties():
    props = JavaProperties.from_string(
        """
#  comment
  # comment
! comment
FOO=BAR
BAZ=DUN\
NO
"""
    )
    assert props.get("FOO") == "BAR"
    assert props.get("BAZ") == "DUNNO"


def test_parse_keytool_printcert_sha256() -> None:
    import textwrap

    output = textwrap.dedent(
        """\
        Owner: CN=Memfault Inc, OU=Memfault, O=Memfault, L=San Francisco, ST=CA, C=US
        SHA256: foo bar
        Certificate fingerprints:
        \t SHA256: 17:47:DC:46:55:D9:72:9E:5B:3A:A9:33:8D:52:53:85:95:A3:56:AA:80:61:86:5C:14:8F:BB:00:DF:FB:4B:4C
        Version: 3
        """
    )
    assert (
        _parse_keytool_printcert_sha256(output, [])
        == "17:47:DC:46:55:D9:72:9E:5B:3A:A9:33:8D:52:53:85:95:A3:56:AA:80:61:86:5C:14:8F:BB:00:DF:FB:4B:4C"
    )


def test_parse_keytool_printcert_sha256_failure() -> None:
    import pytest

    with pytest.raises(Exception, match="Failed to extract SHA256 fingerprint") as excinfo:
        _parse_keytool_printcert_sha256("the output", ["keytool", "cmd", "xyz"])
    assert "keytool cmd xyz" in str(excinfo.value)
    assert "the output" in str(excinfo.value)


def test_parse_apksigner_cert_sha256() -> None:
    import textwrap

    output = textwrap.dedent(
        """\
        Signer #1 certificate DN: CN=Memfault Inc, OU=Memfault, O=Memfault, L=San Francisco, ST=CA, C=US
        Signer #1 certificate SHA-256 digest: 1747dc4655d9729e5b3aa9338d52538595a356aa8061865c148fbb00dffb4b4c
        Signer #2 certificate SHA-256 digest: aa47dc4655d9729e5b3aa9338d52538595a356aa8061865c148fbb00dffb4b4c
        """
    )
    assert _parse_apksigner_cert_sha256(output) == [
        "17:47:DC:46:55:D9:72:9E:5B:3A:A9:33:8D:52:53:85:95:A3:56:AA:80:61:86:5C:14:8F:BB:00:DF:FB:4B:4C",
        "AA:47:DC:46:55:D9:72:9E:5B:3A:A9:33:8D:52:53:85:95:A3:56:AA:80:61:86:5C:14:8F:BB:00:DF:FB:4B:4C",
    ]


def test_check_signatures_mismatch():
    import pytest

    with pytest.raises(Exception, match="signature does not match"):
        _check_signatures(
            output_file="output.txt",
            apk_file="app.apk",
            pem_file="cert.pem",
            apk_cert_sha256s=["AA:BB", "EE:FF"],
            pem_sha256="CC:DD",
        )


def test_check_signatures_match():
    import tempfile

    with tempfile.NamedTemporaryFile(mode="w+") as f:
        _check_signatures(
            output_file=f.name,
            apk_file="app.apk",
            pem_file="cert.pem",
            apk_cert_sha256s=["AA:BB", "CC:DD"],
            pem_sha256="CC:DD",
        )

        assert f.read() == "OK: CC:DD"


def test_apksigner_jar_path_exists():
    assert os.path.exists(_get_apksigner_jar_path())
