version 1.0

workflow primitive {

    meta {
        description: "A stub workflow to test handling WDL's primitive types."
        author: "Hornet"
        email:  "hornet@broadinstitute.org"
    }

    input {
        Boolean     inbool
        File        infile
        Float       infloat
        Int         inint
        String      instring
    }

    output {
        Boolean     outbool   = inbool
        File        outfile   = infile
        Float       outfloat  = infloat
        Int         outint    = inint
        String      outstring = instring
    }
}
