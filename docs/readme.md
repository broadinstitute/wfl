# WorkFlow Launcher Documentation

**GitHub Pages [documentation website](https://broadinstitute.github.io/wfl)**

## Rendering locally

You may wish to launch a local version of our documentation website
to test changes for formatting, link resolution, etc.

Python 3 is requred:
```
cd /path/to/wfl/docs
python3 pip install -r requirements.txt
mkdocs serve
```

Logging output will indicate that changes are being detected,
and point you to where documentation is served (e.g. http://127.0.0.1:8000).
```
INFO    -  Building documentation... 
INFO    -  Cleaning site directory 
WARNING -  A relative path to 'dev-sandbox.md' is included in the 'nav' configuration, which is not found in the documentation files 
WARNING -  A relative path to 'dev-deployment.md' is included in the 'nav' configuration, which is not found in the documentation files 
INFO    -  Documentation built in 1.31 seconds 
[I 210423 10:39:01 server:335] Serving on http://127.0.0.1:8000
INFO    -  Serving on http://127.0.0.1:8000
[I 210423 10:39:01 handlers:62] Start watching changes
INFO    -  Start watching changes
[I 210423 10:39:01 handlers:64] Start detecting changes
INFO    -  Start detecting changes
[I 210423 10:39:09 handlers:135] Browser Connected: http://127.0.0.1:8000/
INFO    -  Browser Connected: http://127.0.0.1:8000/
[I 210423 10:39:17 handlers:135] Browser Connected: http://127.0.0.1:8000/dev-process/
INFO    -  Browser Connected: http://127.0.0.1:8000/dev-process/
...
```

## GitHub Pages Resources
[Relative links in markup files](https://github.blog/2013-01-31-relative-links-in-markup-files/)
allow intra-doc links to work within GitHub Pages website as well as the
repository view.