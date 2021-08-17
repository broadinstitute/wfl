# WorkFlow Launcher Documentation

**GitHub Pages [documentation website](https://broadinstitute.github.io/wfl)**

## Rendering locally

You may wish to launch a local version of our documentation website
to test changes for formatting, link resolution, etc.

Python 3 is required. It is also good practice to use virtualenv:
```
cd /path/to/wfl/docs
virtualenv <<name>>
source <<name>>/bin/activate
python3 -m pip install -r requirements.txt
mkdocs serve
```

Alternatively, you can use make:
```
make docs
. derived/.venv/docs/bin/activate && (  cd derived/docs; python3 -m mkdocs serve  )
```

Logging output will indicate that changes are being detected,
and point you to where documentation is served (e.g. http://127.0.0.1:8000).
```
INFO    -  Building documentation... 
INFO    -  Cleaning site directory 
INFO    -  Documentation built in 1.46 seconds
[I 210423 14:27:04 server:335] Serving on http://127.0.0.1:8000
INFO    -  Serving on http://127.0.0.1:8000
[I 210423 14:27:04 handlers:62] Start watching changes
INFO    -  Start watching changes
[I 210423 14:27:04 handlers:64] Start detecting changes
INFO    -  Start detecting changes
[I 210423 14:27:05 handlers:135] Browser Connected: http://localhost:8000/dev-process/
INFO    -  Browser Connected: http://localhost:8000/dev-process/
...
```

## GitHub Pages Resources
[Relative links in markup files](https://github.blog/2013-01-31-relative-links-in-markup-files/)
allow intra-doc links to work within GitHub Pages website as well as the
repository view.

[Links to headers](https://stackoverflow.com/questions/27981247/github-markdown-same-page-link)
can be determined by following these conversion rules.
Or you can get the link directly from your local docsite instance
by clicking on the anchor icon visible when hovering to the right of the header.
