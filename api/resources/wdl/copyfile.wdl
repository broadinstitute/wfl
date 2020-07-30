version 1.0

task apply {
  input {
    String source
    String destination
  }

  command {
    set -euo pipefail
    GSUTIL=$(
        search=$(which gsutil);
        default="/usr/local/google-cloud-sdk/bin/gsutil";
        if [ -z "$search" ]; then echo "$default"; else echo "$search"; fi
    )

    $GSUTIL cp -L cp.log ~{source} ~{destination}
  }

  output {
    String result = stdout()
  }

  runtime {
    docker: "us.gcr.io/broad-gotc-prod/dsde-toolbox:dev"
  }
}

workflow copyfile {
  input {
    String src
    String dst
  }

  call apply {
    input: source = src, destination = dst
  }
}
