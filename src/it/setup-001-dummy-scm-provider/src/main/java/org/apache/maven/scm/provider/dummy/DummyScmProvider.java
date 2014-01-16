package org.apache.maven.scm.provider.dummy;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.scm.CommandParameters;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.command.checkin.CheckInScmResult;
import org.apache.maven.scm.command.checkout.CheckOutScmResult;
import org.apache.maven.scm.command.status.StatusScmResult;
import org.apache.maven.scm.command.tag.TagScmResult;
import org.apache.maven.scm.provider.AbstractScmProvider;
import org.apache.maven.scm.provider.ScmProviderRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.codehaus.plexus.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A dummy SCM provider used to bypass the {@code ScmCheckModificationsPhase} of the Release Plugin when doing a dry run
 * for integration testing.
 *
 * @plexus.component role="org.apache.maven.scm.provider.ScmProvider" role-hint="dummy"
 */
public class DummyScmProvider
        extends AbstractScmProvider {

    public String getScmType() {
        return "dummy";
    }

    public ScmProviderRepository makeProviderScmRepository(String scmSpecificUrl, char delimiter)
            throws ScmRepositoryException {
        return new DummyScmProviderRepository(scmSpecificUrl);
    }

    protected StatusScmResult status(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return new StatusScmResult("", "", "", true);
    }

    protected CheckInScmResult checkin(ScmProviderRepository repository, ScmFileSet fileSet,
                                       CommandParameters parameters) {
        return new CheckInScmResult("", Collections.emptyList());
    }

    protected TagScmResult tag(ScmProviderRepository repository, ScmFileSet fileSet, CommandParameters parameters)
            throws ScmException {
        return new TagScmResult("", Collections.emptyList());
    }

    protected CheckOutScmResult checkout(ScmProviderRepository repository, ScmFileSet fileSet,
                                         CommandParameters parameters) throws ScmException {
        DummyScmProviderRepository repo = (DummyScmProviderRepository) repository;
        System.out.println("repo = " + repo.getBasedir());
        try {
            fileSet.getBasedir().mkdirs();
            copyDirectory(repo.getBasedir(), fileSet.getBasedir(), "**",
                    "**/target,**/target/**,**/src/secret,**/src/secret/**");
        } catch (IOException e) {
            throw new ScmException(e.getMessage(), e);
        }
        return new CheckOutScmResult("", Collections.emptyList());
    }

    public static void copyDirectory( File sourceDirectory, File destinationDirectory, String includes,
                                      String excludes )
        throws IOException
    {
        if ( !sourceDirectory.exists() )
        {
            return;
        }

        List files = FileUtils.getFileNames( sourceDirectory, includes, excludes, false );

        for ( Iterator i = files.iterator(); i.hasNext(); )
        {
            String fileName = (String) i.next();

            FileUtils.copyFileToDirectory(new File(sourceDirectory, fileName), new File(destinationDirectory, fileName)
                    .getParentFile() );
        }
    }


}
