from __future__ import annotations

import importlib.util
import socket
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


def load_proxy(path: str):
    spec = importlib.util.spec_from_file_location("agent_proxy", Path(path))
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load agent proxy")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


proxy = load_proxy(sys.argv[1])


class ProxyPolicySuite(unittest.TestCase):
    def test_allowlist_matches_only_a_domain_or_its_subdomains(self) -> None:
        rules = ["openai.com"]
        self.assertTrue(proxy.allowed_host("openai.com", rules))
        self.assertTrue(proxy.allowed_host("api.openai.com.", rules))
        self.assertFalse(proxy.allowed_host("notopenai.com", rules))
        self.assertFalse(proxy.allowed_host("openai.com.example.org", rules))

    def test_ip_literals_are_never_allowed(self) -> None:
        self.assertFalse(proxy.allowed_host("93.184.216.34", ["93.184.216.34"]))
        self.assertFalse(proxy.allowed_host("2001:4860:4860::8888", ["example.org"]))

    def test_authority_requires_an_explicit_port(self) -> None:
        self.assertEqual(proxy.parse_authority("example.org:443"), ("example.org", 443))
        self.assertEqual(proxy.parse_authority("[2001:db8::1]:443"), ("2001:db8::1", 443))
        with self.assertRaises(ValueError):
            proxy.parse_authority("example.org")

    @patch("socket.getaddrinfo")
    def test_resolution_discards_non_public_addresses(self, getaddrinfo) -> None:
        getaddrinfo.return_value = [
            (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", 443)),
            (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.216.34", 443)),
        ]
        self.assertEqual(
            proxy.public_addresses("example.org", 443),
            [(socket.AF_INET, socket.SOCK_STREAM, 6, ("93.184.216.34", 443))],
        )


if __name__ == "__main__":
    unittest.main(argv=[sys.argv[0]])
