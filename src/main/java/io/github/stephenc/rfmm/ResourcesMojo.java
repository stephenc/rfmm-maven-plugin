/*
 * Copyright 2014 Stephen Connolly.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.stephenc.rfmm;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Resource;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * If there are any non-scm resources to be refreshed, ensure that they are present / up to date.
 *
 * @author Stephen Connolly
 */
@Mojo(name = "resources", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, requiresProject = true, threadSafe = true)
public class ResourcesMojo extends AbstractMojo {

    /**
     * The character encoding scheme to be applied when filtering resources.
     */
    @Parameter(property = "encoding", defaultValue = "${project.build.sourceEncoding}")
    protected String encoding;
    /**
     * The list of additional filter properties files to be used along with System and project
     * properties, which would be used for the filtering.
     */
    @Parameter(defaultValue = "${project.build.filters}", readonly = true)
    protected List<String> buildFilters;
    /**
     * The list of extra filter properties files to be used along with System properties,
     * project properties, and filter properties files specified in the POM build/filters section,
     * which should be used for the filtering during the current mojo execution.
     */
    @Parameter
    protected List<String> filters;
    /**
     * If false, don't use the filters specified in the build/filters section of the POM when
     * processing resources in this mojo execution.
     */
    @Parameter(defaultValue = "true")
    protected boolean useBuildFilters;
    /**
     *
     */
    @Component(role = MavenResourcesFiltering.class, hint = "default")
    protected MavenResourcesFiltering mavenResourcesFiltering;
    /**
     * Expression preceded with the String won't be interpolated
     * \${foo} will be replaced with ${foo}
     */
    @Parameter(property = "maven.resources.escapeString")
    protected String escapeString;
    /**
     * Copy any empty directories included in the Resources.
     */
    @Parameter(property = "maven.resources.includeEmptyDirs", defaultValue = "false")
    protected boolean includeEmptyDirs;
    /**
     * Additional file extensions to not apply filtering (already defined are : jpg, jpeg, gif, bmp, png)
     */
    @Parameter
    protected List<String> nonFilteredFileExtensions;
    /**
     * Whether to escape backslashes and colons in windows-style paths.
     */
    @Parameter(property = "maven.resources.escapeWindowsPaths", defaultValue = "true")
    protected boolean escapeWindowsPaths;
    /**
     * Set of delimiters for expressions to filter within the resources. These delimiters are specified in the
     * form 'beginToken*endToken'. If no '*' is given, the delimiter is assumed to be the same for start and end.
     * </p><p>
     * So, the default filtering delimiters might be specified as:
     * </p>
     * <pre>
     * &lt;delimiters&gt;
     *   &lt;delimiter&gt;${*}&lt;/delimiter&gt;
     *   &lt;delimiter&gt;@&lt;/delimiter&gt;
     * &lt;/delimiters&gt;
     * </pre>
     * <p>
     * Since the '@' delimiter is the same on both ends, we don't need to specify '@*@' (though we can).
     * </p>
     */
    @Parameter
    protected List<String> delimiters;
    /**
     */
    @Parameter(defaultValue = "true")
    protected boolean useDefaultDelimiters;
    /**
     * The output directory into which to copy the resources.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private File outputDirectory;
    /**
     * The list of resources we want to transfer.
     */
    @Parameter()
    private List<Resource> resources;
    /**
     * The offset from the execution root of the checkout invoked by release:perform to the parent execution root.
     * <p/>
     * As the default checkout path by the release plugin is {@code target/checkout/} the default offset just has to
     * move up two levels of directory.
     */
    @Parameter(defaultValue = "../../", property = "rfmm.offset")
    private String offset;
    @Component
    private MavenProject project;
    @Component
    private MavenSession session;
    /**
     * Overwrite existing files even if the destination files are newer.
     */
    @Parameter(property = "maven.resources.overwrite", defaultValue = "false")
    private boolean overwrite;
    /**
     * List of plexus components hint which implements {@link MavenResourcesFiltering#filterResources
     * (MavenResourcesExecution)}.
     * They will be executed after the resources copying/filtering.
     */
    @Parameter
    private List<String> mavenFilteringHints;
    /**
     */
    private PlexusContainer plexusContainer;
    /**
     */
    private List<MavenResourcesFiltering> mavenFilteringComponents = new ArrayList<MavenResourcesFiltering>();
    /**
     * stop searching endToken at the end of line
     */
    @Parameter(property = "maven.resources.supportMultiLineFiltering", defaultValue = "false")
    private boolean supportMultiLineFiltering;

    /**
     * Returns the path of one File relative to another.
     *
     * @param target the target directory
     * @param base   the base directory
     * @return target's path relative to the base directory
     */
    public static String getRelativePath(File target, File base) {
        String baseStr;
        try {
            baseStr = base.getCanonicalPath();
        } catch (IOException e) {
            baseStr = base.getAbsolutePath();
        }
        String targetStr;
        try {
            targetStr = target.getCanonicalPath();
        } catch (IOException e) {
            targetStr = target.getAbsolutePath();
        }
        String[] baseComponents = baseStr.split(Pattern.quote(File.separator));
        String[] targetComponents = targetStr.split(Pattern.quote(File.separator));

        // skip common components
        int index = 0;
        for (; index < targetComponents.length && index < baseComponents.length; ++index) {
            if (!targetComponents[index].equals(baseComponents[index])) {
                break;
            }
        }

        StringBuilder result = new StringBuilder();
        if (index != baseComponents.length) {
            // backtrack to base directory
            for (int i = index; i < baseComponents.length; ++i) {
                result.append(".." + File.separator);
            }
        }
        for (; index < targetComponents.length; ++index) {
            result.append(targetComponents[index] + File.separator);
        }
        if (result.length() > 0 && !target.getPath().endsWith("/") && !target.getPath().endsWith("\\")) {
            // remove final path separator
            result.delete(result.length() - File.separator.length(), result.length());
        }
        return result.toString();
    }

    public void contextualize(Context context)
            throws ContextException {
        plexusContainer = (PlexusContainer) context.get(PlexusConstants.PLEXUS_KEY);
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        if (resources == null || resources.isEmpty()) {
            resources = new ArrayList<Resource>(1);
            final Resource resource = new Resource();
            resource.setDirectory("src/secret/resources");
            resource.setFiltering(false);
            resources.add(resource);
        }
        if (!outputDirectory.isDirectory()) {
            outputDirectory.mkdirs();
        }
        File sessionExecutionRoot;
        try {
            sessionExecutionRoot = new File(session.getExecutionRootDirectory()).getCanonicalFile();
        } catch (IOException e) {
            sessionExecutionRoot = new File(session.getExecutionRootDirectory());
        }
        getLog().debug("Session execution root: " + sessionExecutionRoot);
        final File sessionBaseDir = project.getBasedir();
        final String relativePath = getRelativePath(sessionBaseDir, sessionExecutionRoot);
        getLog().debug("Project path relative to root: " + relativePath);
        File candidateExecutionRoot;
        try {
            candidateExecutionRoot = new File(sessionExecutionRoot, offset).getCanonicalFile();
        } catch (IOException e) {
            candidateExecutionRoot = new File(sessionExecutionRoot, offset).getAbsoluteFile();
        }
        getLog().debug("Candidate execution root: " + candidateExecutionRoot);
        File candidateBaseDir = null;
        if (candidateExecutionRoot.equals(sessionExecutionRoot)) {
            getLog().debug(
                    "Execution root is sufficiently close to the root of the filesystem that we cannot be a release "
                            + "build");
        } else {
            candidateBaseDir = new File(candidateExecutionRoot, relativePath);
            getLog().debug("Candidate project directory: " + candidateBaseDir);
            if (!candidateBaseDir.isDirectory()) {
                getLog().debug("As there is no directory at the candidate path, we cannot be a release build");
                candidateBaseDir = null;
            }
        }
        File candidateProjectFile;
        if (candidateBaseDir == null) {
            candidateProjectFile = project.getFile();
        } else {
            candidateProjectFile = new File(candidateBaseDir, project.getFile().getName());
            if (!isGroupIdArtifactIdMatch(candidateProjectFile)) {
                candidateProjectFile = project.getFile();
            }
        }

        try {

            if (StringUtils.isEmpty(encoding) && isFilteringEnabled(getResources())) {
                getLog().warn("File encoding has not been set, using platform encoding " + ReaderFactory.FILE_ENCODING
                        + ", i.e. build is platform dependent!");
            }

            List<String> filters = getCombinedFiltersList();

            MavenProject project = null;
            try {
                project = (MavenProject) this.project.clone();
                project.setFile(candidateProjectFile);
            } catch (CloneNotSupportedException e) {
                throw new MojoExecutionException("Could not clone project");
            }

            MavenResourcesExecution mavenResourcesExecution =
                    new MavenResourcesExecution(getResources(), getOutputDirectory(), project, encoding, filters,
                            Collections.<String>emptyList(), session);

            mavenResourcesExecution.setEscapeWindowsPaths(escapeWindowsPaths);

            // never include project build filters in this call, since we've already accounted for the POM build filters
            // above, in getCombinedFiltersList().
            mavenResourcesExecution.setInjectProjectBuildFilters(false);

            mavenResourcesExecution.setEscapeString(escapeString);
            mavenResourcesExecution.setOverwrite(overwrite);
            mavenResourcesExecution.setIncludeEmptyDirs(includeEmptyDirs);
            mavenResourcesExecution.setSupportMultiLineFiltering(supportMultiLineFiltering);

            // if these are NOT set, just use the defaults, which are '${*}' and '@'.
            if (delimiters != null && !delimiters.isEmpty()) {
                LinkedHashSet<String> delims = new LinkedHashSet<String>();
                if (useDefaultDelimiters) {
                    delims.addAll(mavenResourcesExecution.getDelimiters());
                }

                for (String delim : delimiters) {
                    if (delim == null) {
                        // FIXME: ${filter:*} could also trigger this condition. Need a better long-term solution.
                        delims.add("${*}");
                    } else {
                        delims.add(delim);
                    }
                }

                mavenResourcesExecution.setDelimiters(delims);
            }

            if (nonFilteredFileExtensions != null) {
                mavenResourcesExecution.setNonFilteredFileExtensions(nonFilteredFileExtensions);
            }
            mavenResourcesFiltering.filterResources(mavenResourcesExecution);

            executeUserFilterComponents(mavenResourcesExecution);
        } catch (MavenFilteringException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private boolean isGroupIdArtifactIdMatch(File pomFile) {
        if (pomFile == null || !pomFile.isFile()) {
            return false;
        }
        MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();
        FileReader fileReader = null;
        try {
            fileReader = new FileReader(pomFile);
            final Model model = xpp3Reader.read(fileReader);
            String groupId = model.getGroupId();
            String artifactId = model.getArtifactId();
            if (model.getParent() != null) {
                if (groupId == null) {
                    groupId = model.getParent().getGroupId();
                }
                if (artifactId == null) {
                    artifactId = model.getParent().getArtifactId();
                }
            }
            return StringUtils.equals(groupId, project.getGroupId())
                    && StringUtils.equals(artifactId, project.getArtifactId());
        } catch (FileNotFoundException e) {
            return false;
        } catch (XmlPullParserException e) {
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            IOUtil.close(fileReader);
        }
    }

    protected void executeUserFilterComponents(MavenResourcesExecution mavenResourcesExecution)
            throws MojoExecutionException, MavenFilteringException {

        if (mavenFilteringHints != null) {
            for (String hint : mavenFilteringHints) {
                try {
                    mavenFilteringComponents.add(
                            (MavenResourcesFiltering) plexusContainer.lookup(MavenResourcesFiltering.class.getName(),
                                    hint));
                } catch (ComponentLookupException e) {
                    throw new MojoExecutionException(e.getMessage(), e);
                }
            }
        } else {
            getLog().debug("no use filter components");
        }

        if (mavenFilteringComponents != null && !mavenFilteringComponents.isEmpty()) {
            getLog().debug("execute user filters");
            for (MavenResourcesFiltering filter : mavenFilteringComponents) {
                filter.filterResources(mavenResourcesExecution);
            }
        }
    }

    protected List<String> getCombinedFiltersList() {
        if (filters == null || filters.isEmpty()) {
            return useBuildFilters ? buildFilters : null;
        } else {
            List<String> result = new ArrayList<String>();

            if (useBuildFilters && buildFilters != null && !buildFilters.isEmpty()) {
                result.addAll(buildFilters);
            }

            result.addAll(filters);

            return result;
        }
    }

    /**
     * Determines whether filtering has been enabled for any resource.
     *
     * @param resources The set of resources to check for filtering, may be <code>null</code>.
     * @return <code>true</code> if at least one resource uses filtering, <code>false</code> otherwise.
     */
    private boolean isFilteringEnabled(Collection<Resource> resources) {
        if (resources != null) {
            for (Resource resource : resources) {
                if (resource.isFiltering()) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<Resource> getResources() {
        return resources;
    }

    public void setResources(List<Resource> resources) {
        this.resources = resources;
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public boolean isOverwrite() {
        return overwrite;
    }

    public void setOverwrite(boolean overwrite) {
        this.overwrite = overwrite;
    }

    public boolean isIncludeEmptyDirs() {
        return includeEmptyDirs;
    }

    public void setIncludeEmptyDirs(boolean includeEmptyDirs) {
        this.includeEmptyDirs = includeEmptyDirs;
    }

    public List<String> getFilters() {
        return filters;
    }

    public void setFilters(List<String> filters) {
        this.filters = filters;
    }

    public List<String> getDelimiters() {
        return delimiters;
    }

    public void setDelimiters(List<String> delimiters) {
        this.delimiters = delimiters;
    }

    public boolean isUseDefaultDelimiters() {
        return useDefaultDelimiters;
    }

    public void setUseDefaultDelimiters(boolean useDefaultDelimiters) {
        this.useDefaultDelimiters = useDefaultDelimiters;
    }
}
