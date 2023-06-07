/*
 * Copyright 2013-2023, Seqera Labs
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
 *
 */

package io.seqera.wave.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.io.File;

import io.seqera.wave.config.CondaOpts;
import io.seqera.wave.config.SpackOpts;
import org.apache.commons.lang3.StringUtils;
import org.yaml.snakeyaml.Yaml;

/**
 * Helper class to create Dockerfile for Conda and Spack package managers
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class DockerHelper {

    static public List<String> spackPackagesToList(String packages) {
        if( packages==null || packages.isEmpty() )
            return null;
        final List<String> entries = Arrays.asList(packages.split(" "));
        final List<String> result = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for( String it : entries ) {
            if( it==null || it.isEmpty() || it.isBlank() )
                continue;
            if( !Character.isLetterOrDigit(it.charAt(0)) || it.contains("=") ) {
              current.add(it);
            }
            else {
                if( current.size()>0 )
                    result.add(String.join(" ",current));
                current = new ArrayList<>();
                current.add(it);
            }
        }
        // remaining entries
        if( current.size()>0 )
            result.add(String.join(" ",current));
        return result;
    }

    static public String spackPackagesToSpackYaml(String packages) {
        final List<String> specs = spackPackagesToList(packages);

        final Map<String,Object> concretizer = new LinkedHashMap<>();
        concretizer.put("unify", true);
        concretizer.put("reuse", false);

        final Map<String,Object> spack = new LinkedHashMap<>();
        spack.put("specs", specs);
        spack.put("concretizer", concretizer);

        final Map<String,Object> root = new LinkedHashMap<>();
        root.put("spack", spack);

        return new Yaml().dump(root);
    }

    static public Path spackPackagesToSpackFile(String packages) {
        final String yaml = spackPackagesToSpackYaml(packages);
        if( yaml==null || yaml.length()==0 )
            return null;
        try {
            final File tempFile = File.createTempFile("nf-spack", ".yaml");
            tempFile.deleteOnExit();
            final Path result = tempFile.toPath();
            Files.write(result, yaml.getBytes());
            return result;
        }
        catch (IOException e) {
            throw new IllegalStateException("Unable to write temporary Spack environment file - Reason: " + e.getMessage(), e);
        }
    }

    static public String spackFileToDockerFile(String spackArch, SpackOpts opts) {
        // create bindings
        final Map<String,String> binding = spackBinding(spackArch, opts);
        //  return the template
        return renderTemplate0("/templates/spack/dockerfile-spack-file.txt", binding);
    }

    static private Map<String,String> spackBinding(String spackArch, SpackOpts opts) {
        final Map<String,String> binding = new HashMap<>();
        binding.put("builder_image", opts.builderImage);
        binding.put("spack_arch", spackArch);
        binding.put("checksum_string", opts.checksum ? "" : "-n ");
        binding.put("runner_image", opts.runnerImage);
        binding.put("os_packages", opts.osPackages);
        binding.put("add_commands", joinCommands(opts.commands));
        return binding;
    }

    static public String condaPackagesToDockerFile(String packages, List<String> condaChannels, CondaOpts opts) {
        final List<String> channels0 = condaChannels!=null ? condaChannels : List.of();
        final String channelsOpts = channels0.stream().map(it -> "-c "+it).collect(Collectors.joining(" "));
        final String image = opts.mambaImage;
        final String target = packages.startsWith("http://") || packages.startsWith("https://")
                ? "-f " + packages
                : packages;
        final Map<String,String> binding = new HashMap<>();
        binding.put("base_image", image);
        binding.put("channel_opts", channelsOpts);
        binding.put("target", target);
        binding.put("base_packages", mambaInstallBasePackage0(opts.basePackages));

        final String result = renderTemplate0("/templates/conda/dockerfile-conda-packages.txt", binding) ;
        return addCommands(result, opts.commands);
    }

    static public String condaFileToDockerFile(CondaOpts opts) {
        // create the binding map
        final Map<String,String> binding = new HashMap<>();
        binding.put("base_image", opts.mambaImage);
        binding.put("base_packages", mambaInstallBasePackage0(opts.basePackages));

        final String result = renderTemplate0("/templates/conda/dockerfile-conda-file.txt", binding);
        return addCommands(result, opts.commands);
    }

    static private String renderTemplate0(String templatePath, Map<String,String> binding) {
        final URL template = DockerHelper.class.getResource(templatePath);
        if( template==null )
            throw new IllegalStateException(String.format("Unable to load template '%s' from classpath", templatePath));
        try {
            final InputStream reader = template.openStream();
            return TemplateRenderer.render(reader, binding);
        }
        catch (IOException e) {
            throw new IllegalStateException(String.format("Unable to read classpath template '%s'", templatePath), e);
        }
    }

    private static String mambaInstallBasePackage0(String basePackages) {
        return !StringUtils.isEmpty(basePackages)
                ? String.format("&& micromamba install -y -n base %s \\", basePackages)
                : null;
    }

    static private String addCommands(String result, List<String> commands) {
        if( commands==null || commands.isEmpty() )
            return result;
        for( String cmd : commands ) {
            result += cmd + "\n";
        }
        return result;
    }

    static private String joinCommands(List<String> commands) {
        if( commands==null || commands.size()==0 )
            return null;
        StringBuilder result = new StringBuilder();
        for( String cmd : commands ) {
            if( result.length()>0 )
                result.append("\n");
            result.append(cmd);
        }
        return result.toString();
    }
}