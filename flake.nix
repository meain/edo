{
  description = "Edo — Android agent app dev shell (JDK 17 toolchain)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        jdk = pkgs.temurin-bin-17;
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [ jdk pkgs.gradle ];

          # Android SDK is managed outside nix (see local.properties: ~/Android/sdk-edo).
          # The flake only provides the JDK 17 toolchain Gradle needs.
          JAVA_HOME = "${jdk}";

          shellHook = ''
            export ANDROID_HOME="$HOME/Android/sdk-edo"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export PATH="$ANDROID_HOME/platform-tools:$PATH"
            echo "Edo dev shell — JDK $(java -version 2>&1 | head -n1 | sed 's/.*\"\(.*\)\".*/\1/')"
            echo "ANDROID_HOME=$ANDROID_HOME"
          '';
        };
      });
}
