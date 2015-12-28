Thread pooled TCP Chat server
=============================

Uses Java 7 features.  
Relies on `java.util.concurrent.Executors` for thread pooling, `java.net.*`  for networking.  

Building
--------
`compile.sh`  
Requires a java compiler in (javac) in the $PATH, builds class files in the current directory.

Running
-------
`start.sh <port> [threads] [backlog]`  
Requires said class files be present in the current directory.
