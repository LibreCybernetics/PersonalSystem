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
          config.allowUnfreePredicate = package: builtins.elem (lib.getName package) [ "claude-code" ];
        };

        # The JDK the build, CI and produced launcher run on.
        # The headless output still contains javac and the FFM API. Unlike the full output it does
        # not put GTK 3 on the JVM RUNPATH, which would make GTK 4 refuse to initialize in Java-GI.
        jdk = pkgs.jdk25_headless;

        # Java-GI opens each introspected namespace by SONAME, so every directly bound native
        # library must be discoverable rather than only reachable as GTK's transitive dependency.
        gtkLibraries = with pkgs; [
          cairo
          gdk-pixbuf
          glib
          graphene
          gtk4
          harfbuzz
          libadwaita
          pango
        ];

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

        # Development package for the native GNOME surface. sbt owns the JVM classpath while Nix
        # owns the JDK and native GTK/libadwaita closure; the generated launcher is uncached and is
        # therefore always materialized before execution.
        guiRunner = pkgs.writeShellApplication {
          name = "noesis-gui";
          runtimeInputs = [
            jdk
            sbt2
            pkgs.coreutils
            pkgs.findutils
            pkgs.xvfb-run
          ]
          ++ gtkLibraries;
          text = ''
            export LD_LIBRARY_PATH=${lib.makeLibraryPath gtkLibraries}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
            cache_root="''${XDG_CACHE_HOME:-$HOME/.cache}/noesis-gui"
            build_root="$cache_root/${builtins.baseNameOf self.outPath}"
            if [ ! -f "$build_root/.source-ready" ]; then
              mkdir -p "$build_root"
              cp -R ${self.outPath}/. "$build_root/"
              chmod -R u+w "$build_root"
              touch "$build_root/.source-ready"
            fi
            cd "$build_root"
            sbt -batch gui/guiLauncher
            launcher="$(find target/out -type f -name noesis-gui -perm -u+x -print -quit)"
            if [ -z "$launcher" ]; then
              echo "Noesis did not start" >&2
              echo "the GUI launcher was not produced" >&2
              echo "run nix develop --command sbt -batch gui/guiLauncher" >&2
              exit 1
            fi
            exec "$launcher" "$@"
          '';
        };

        gui = pkgs.runCommand "noesis-gui" { } ''
          mkdir -p "$out/bin" "$out/share/applications" "$out/share/metainfo"
          mkdir -p "$out/share/icons/hicolor/scalable/apps"
          ln -s ${guiRunner}/bin/noesis-gui "$out/bin/noesis-gui"
          cp ${./modules/gui/src/main/resources/dev.librecybernetics.Noesis.desktop} \
            "$out/share/applications/dev.librecybernetics.Noesis.desktop"
          cp ${./modules/gui/src/main/resources/dev.librecybernetics.Noesis.metainfo.xml} \
            "$out/share/metainfo/dev.librecybernetics.Noesis.metainfo.xml"
          cp ${./modules/gui/src/main/resources/dev.librecybernetics.Noesis.svg} \
            "$out/share/icons/hicolor/scalable/apps/dev.librecybernetics.Noesis.svg"
        '';
      in
      {
        packages = {
          agent-environment = agentEnvironment;
          agent-session = agentSession;
          agent-run = agentRun;
          codex-agent = codexAgent;
          claude-agent = claudeAgent;
          agent-shell = agentShell;
          inherit gui;
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
          gui = {
            type = "app";
            program = "${gui}/bin/noesis-gui";
          };
        };

        checks.agent-sandbox-sources =
          pkgs.runCommand "agent-sandbox-sources"
            {
              nativeBuildInputs = [
                pkgs.python3
                pkgs.shellcheck
              ];
            }
            ''
              shellcheck -s bash ${./nix/agent-session.sh} ${./nix/agent-run.sh}
              python3 -m py_compile ${./nix/agent-proxy.py}
              python3 ${./nix/agent-proxy-test.py} ${./nix/agent-proxy.py}
              touch "$out"
            '';

        checks.github-actions =
          pkgs.runCommand "github-actions"
            {
              nativeBuildInputs = [
                pkgs.actionlint
                pkgs.shellcheck
              ];
            }
            ''
              actionlint ${./.github/workflows/ci.yml} ${./.github/workflows/mutation.yml}
              touch "$out"
            '';

        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.actionlint
            jdk
            sbt2
            pkgs.coursier
            pkgs.jq
            pkgs.scala-cli
            pkgs.metals
            pkgs.nixfmt
            pkgs.python3Packages.diff-cover
            pkgs.shellcheck
            pkgs.gtk4
            pkgs.libadwaita
            pkgs.xvfb-run
          ];

          JAVA_HOME = "${jdk}";
          LD_LIBRARY_PATH = lib.makeLibraryPath gtkLibraries;

          shellHook = ''
            {
              echo "Noesis dev shell — sbt $(sbt --script-version 2>/dev/null || echo 2.0.4), JDK ${jdk.version}"
              echo "  see TESTING.md    run explicit test suites and verification"
              echo "  sbt cli/run --help  exercise the CLI"
              echo "  nix run .#gui       launch the GNOME application"
            } >&2
          '';
        };

        formatter = pkgs.nixfmt;
      }
    );
}
