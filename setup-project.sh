#! /bin/sh -x

#Download jars to the cache to add to the compilation classpath:
mkdir -p ~/.cache/itw-coding
cd ~/.cache/itw-coding
curl -o tagsoup.jar https://repo1.maven.org/maven2/org/ccil/cowan/tagsoup/tagsoup/1.2.1/tagsoup-1.2.1.jar
curl -o mslinks.jar https://repo1.maven.org/maven2/com/github/vatbub/mslinks/0.0.2/mslinks-0.0.2.jar  git clone git@github.com:martinhickson/IcedTea-Web-1.git
curl -o rhino.jar https://repo1.maven.org/maven2/org/mozilla/rhino/1.7.14/rhino-1.7.14.jar
curl -o commonscompress.jar https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.26.0/commons-compress-1.26.0.jar
curl -L -o pack.jar https://github.com/martinhickson/pack200/releases/download/pack200-11.0.2/pack200-11.0.2.jar