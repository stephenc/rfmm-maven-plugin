<!--
   Copyright 2014 Stephen Connolly.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

Release From My Machine Maven Plugin
====================================

[![Build Status](https://buildhive.cloudbees.com/job/stephenc/job/rfmm-maven-plugin/badge/icon)](https://buildhive.cloudbees.com/job/stephenc/job/rfmm-maven-plugin/)

There is an occasional situation that this plugin aims to address. Here is the use case:

  * You have some resources for your project that cannot be kept in source control.
  * The resources have to end up in the final artifact.
  * Developers who are allowed to run releases manually copy the files into place
  * You want to use the maven release plugin to cut releases.

How to use
----------

Add the following to your `pom.xml`

      <plugin>
        <groupId>io.github.stephenc.maven</groupId>
        <artifactId>rfmm-maven-plugin</artifactId>
        <version><!-- the latest version goes here --></version>
        <executions>
          <execution>
            <goals>
              <goal>resources</goal>
            </goals>
          </execution>
        </executions>
      </plugin>

Put your secret resources in `src/secret/resources` and don't check them in to source control (you'll need to add them
to an ignores list also though)

Regular builds will have the files copied to `target/classes` just like normal resources are.

A build with the Maven Release Plugin will also look for the secret resources in the original execution root so
when `release:perform` is running a forked build in `target/checkout` the files will be copied correctly... even with
a multi-module project.
