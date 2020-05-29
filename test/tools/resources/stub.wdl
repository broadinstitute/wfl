task hello
{
    String name
    command
    {
        echo Hello ${name}
    }
    output
    {
        String response = read_string(stdout())
    }
    runtime
    {
        docker: "ubuntu:latest"
    }
}

workflow stub
{
    call hello
    output
    {
        hello.response
    }
}
