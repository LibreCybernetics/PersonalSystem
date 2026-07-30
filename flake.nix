{
  description = "Noesis — a single-user knowledge & learning system on a formal knowledge representation";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        # The JDK the build and the produced launcher run on. sbt 2 requires 17+;
        # 21 is the current LTS and what CI should match.
        jdk = pkgs.jdk21;

        # nixpkgs currently ships sbt 1.x. The sbt 2.x distribution has an
        # identical layout, so overriding version + src is enough to keep nix
        # the single source of truth for the toolchain.
        sbt2 = (pkgs.sbt.override { jre = jdk; }).overrideAttrs (old: {
          version = "2.0.4";
          src = pkgs.fetchurl {
            url = "https://github.com/sbt/sbt/releases/download/v2.0.4/sbt-2.0.4.tgz";
            hash = "sha256-EyU+56ixn2D4xtwQAklhnfGe2Iafi+eDq40gau39w2Y=";
          };
        });
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            jdk
            sbt2
            pkgs.coursier
            pkgs.scala-cli
            pkgs.metals
            pkgs.nixfmt-rfc-style
          ];

          JAVA_HOME = "${jdk}";

          shellHook = ''
            echo "Noesis dev shell — sbt $(sbt --script-version 2>/dev/null || echo 2.0.4), JDK ${jdk.version}"
            echo "  sbt test          run the test suite"
            echo "  sbt cli/run --help  exercise the CLI"
          '';
        };

        formatter = pkgs.nixfmt-rfc-style;
      }
    );
}
