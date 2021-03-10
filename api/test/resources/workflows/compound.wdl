version 1.0

struct Struct {
    Int value
}

workflow compound {

    meta {
        description: "A stub workflow to test handling WDL's compound types."
        author: "Hornet"
        email:  "hornet@broadinstitute.org"
    }

    input {
        Array[Boolean]     inarray
        Map[String, File]  inmap
        String?            inoptional
        Pair[Int, Float]   inpair
        Struct             instruct
    }

    output {
        Array[Boolean]     outarray       = inarray
        Map[String, File]  outmap         = inmap
        String?            outoptional    = inoptional
        Pair[Int, Float]   outpair        = inpair
        Struct             outstruct      = instruct
    }
}
