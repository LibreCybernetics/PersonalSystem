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
        lib = nixpkgs.lib;
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfreePredicate =
            package: builtins.elem (lib.getName package) [ "claude-code" ];
        };

        # The JDK the build, CI and produced launcher run on.
        jdk = pkgs.jdk25;

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

        # Keep the visible Nix store small enough to audit. The sandbox binds
        # this environment's closure path by path instead of exposing the host's
        # entire /nix/store.
        agentEnvironment = pkgs.buildEnv {
          name = "noesis-agent-environment";
          paths = [
            jdk
            sbt2
            pkgs.bashInteractive
            pkgs.bubblewrap
            pkgs.cacert
            pkgs.claude-code
            pkgs.codex
            pkgs.coreutils
            pkgs.coursier
            pkgs.diffutils
            pkgs.file
            pkgs.findutils
            pkgs.gawk
            pkgs.git
            pkgs.gnugrep
            pkgs.gnused
            pkgs.gnutar
            pkgs.gzip
            pkgs.less
            pkgs.metals
            pkgs.patch
            pkgs.scala-cli
            pkgs.socat
            pkgs.unzip
            pkgs.which
            pkgs.zip
          ];
          pathsToLink = [ "/bin" ];
        };

        agentClosure = pkgs.closureInfo {
          rootPaths = [ agentEnvironment ];
        };

        agentProxy = pkgs.writeShellApplication {
          name = "noesis-agent-proxy";
          runtimeInputs = [ pkgs.python3 ];
          text = ''
            exec python3 ${./nix/agent-proxy.py} "$@"
          '';
        };

        agentSession = pkgs.writeShellApplication {
          name = "noesis-agent-session";
          runtimeInputs = [
            pkgs.coreutils
            pkgs.findutils
            pkgs.git
            pkgs.gnugrep
            pkgs.gnutar
          ];
          text = builtins.readFile ./nix/agent-session.sh;
        };

        agentRun = pkgs.writeShellApplication {
          name = "noesis-agent-run";
          # agent-run contains a single-quoted program intentionally evaluated
          # by the shell inside Bubblewrap, not by this generated wrapper.
          excludeShellChecks = [ "SC2016" ];
          runtimeInputs = [
            pkgs.bubblewrap
            pkgs.coreutils
            pkgs.systemd
          ];
          text = ''
            export NOESIS_AGENT_BWRAP=${lib.escapeShellArg "${pkgs.bubblewrap}"}
            export NOESIS_AGENT_CA_BUNDLE=${lib.escapeShellArg "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt"}
            export NOESIS_AGENT_CLOSURE=${lib.escapeShellArg "${agentClosure}/store-paths"}
            export NOESIS_AGENT_ENV=${lib.escapeShellArg "${agentEnvironment}"}
            export NOESIS_AGENT_JAVA=${lib.escapeShellArg "${jdk}"}
            export NOESIS_AGENT_PROXY=${lib.escapeShellArg "${agentProxy}"}
            ${builtins.readFile ./nix/agent-run.sh}
          '';
        };

        codexAgent = pkgs.writeShellApplication {
          name = "noesis-codex-agent";
          runtimeInputs = [ agentRun ];
          text = ''
            exec noesis-agent-run codex "$@"
          '';
        };

        claudeAgent = pkgs.writeShellApplication {
          name = "noesis-claude-agent";
          runtimeInputs = [ agentRun ];
          text = ''
            exec noesis-agent-run claude "$@"
          '';
        };

        agentShell = pkgs.writeShellApplication {
          name = "noesis-agent-shell";
          runtimeInputs = [ agentRun ];
          text = ''
            exec noesis-agent-run shell "$@"
          '';
        };
      in
      {
        packages = {
          agent-environment = agentEnvironment;
          agent-session = agentSession;
          agent-run = agentRun;
          codex-agent = codexAgent;
          claude-agent = claudeAgent;
          agent-shell = agentShell;
        };

        apps = {
          agent-session = {
            type = "app";
            program = "${agentSession}/bin/noesis-agent-session";
          };
          codex-agent = {
            type = "app";
            program = "${codexAgent}/bin/noesis-codex-agent";
          };
          claude-agent = {
            type = "app";
            program = "${claudeAgent}/bin/noesis-claude-agent";
          };
          agent-shell = {
            type = "app";
            program = "${agentShell}/bin/noesis-agent-shell";
          };
        };

        checks.agent-sandbox-sources = pkgs.runCommand "agent-sandbox-sources" {
          nativeBuildInputs = [
            pkgs.python3
            pkgs.shellcheck
          ];
        } ''
          shellcheck -s bash ${./nix/agent-session.sh} ${./nix/agent-run.sh}
          python3 -m py_compile ${./nix/agent-proxy.py}
          python3 ${./nix/agent-proxy-test.py} ${./nix/agent-proxy.py}
          touch "$out"
        '';

        devShells.default = pkgs.mkShell {
          packages = [
            jdk
            sbt2
            pkgs.coursier
            pkgs.scala-cli
            pkgs.metals
            pkgs.nixfmt
          ];

          JAVA_HOME = "${jdk}";

          shellHook = ''
            echo "Noesis dev shell — sbt $(sbt --script-version 2>/dev/null || echo 2.0.4), JDK ${jdk.version}"
            echo "  see TESTING.md    run explicit test suites and verification"
            echo "  sbt cli/run --help  exercise the CLI"
          '';
        };

        formatter = pkgs.nixfmt;
      }
    );
}
