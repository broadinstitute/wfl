version 1.0

struct Struct {
    Int value
}

workflow compound {

    meta {
        description: "A stub workflow to test handling WDL compound types."
        author: "Hornet"
        email:  "hornet@broadinstitute.org"
    }

    input {
        Array[String]      inarray
        Map[String, File]  inmap
        File?              inoptional
        Pair[Int, Float]   inpair
        Struct             instruct
    }

    output {
        Array[Boolean]     outarray       = inarray
        Map[String, File]  outmap         = inmap
        File?              outoptional    = inoptional
        Pair[Int, Float]   outpair        = inpair
        Struct             outstruct      = instruct
    }
}
