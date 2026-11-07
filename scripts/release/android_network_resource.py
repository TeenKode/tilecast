"""Resolve the network policy resource after Android Release path shortening."""
import re
import sys


def network_resource_path(table: str) -> str:
    entry = re.search(
        r"(?m)^\s*resource 0x[0-9a-fA-F]+ [^\r\n]*:xml/tilecast_network_security_config:[^\r\n]*"
        r"(?:(?!\n\s*(?:spec )?resource 0x)[\s\S])*",
        table,
    )
    if entry:
        paths = re.findall(r'"(res/[^"\r\n]+\.xml)"', entry.group())
        if len(set(paths)) == 1:
            return paths[0]
    raise ValueError("Cannot resolve the compiled Tilecast network security policy")


if __name__ == "__main__":
    print(network_resource_path(sys.stdin.read()))
