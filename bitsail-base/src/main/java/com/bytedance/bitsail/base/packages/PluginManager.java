/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytedance.bitsail.base.packages;

import com.bytedance.bitsail.base.version.VersionHolder;
import com.bytedance.bitsail.common.BitSailException;
import com.bytedance.bitsail.common.exception.CommonErrorCode;
import com.bytedance.bitsail.common.util.FastJsonUtil;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import lombok.Builder;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class PluginManager {

  private static final String PLUGIN_NAME_KEY = "name";
  private static final String PLUGIN_CLASS_KEY = "class";
  private static final String PLUGIN_CLASS_NAME_LIST_KEY = "classes";
  private static final String PLUGIN_LIB_KEY = "libs";
  private static final String SEPARATOR = File.separator;
  private static final String FALL_BACK_JAR_NAME = "bitsail-streaming";
  private final String directory;
  /**
   * Mapping for the plugin's name and plugin's uri.
   */
  private final Map<String, List<URL>> pluginToPluginLibsMap = new ConcurrentHashMap<>();
  @Getter
  private final Map<String, String> pluginClassNameToBitSailIoMap = new ConcurrentHashMap<>();
  private final Map<String, Plugin> className2Plugin = new HashMap<>();
  private final Map<String, Plugin> pluginName2Plugin = new HashMap<>();
  private final boolean dryRun;
  private final String pluginLibDir;
  private final String pluginConfDir;
  private final boolean canFallback;

  @Builder
  PluginManager(String path, boolean dryRun, boolean dynamicLoad, String pluginLibDir, String pluginConfDir) {
    log.debug("Starting to load BitSail plugin jar dynamically, current classloader is: [{}], thread context classloader is: [{}]",
        getClass().getClassLoader(), Thread.currentThread().getContextClassLoader());
    directory = path.substring(0, path.lastIndexOf(SEPARATOR) + 1);
    log.info("User jar directory is {}", directory);
    this.dryRun = dryRun;
    canFallback = false;
    this.pluginLibDir = pluginLibDir;
    this.pluginConfDir = pluginConfDir;

    if (dynamicLoad) {
      this.loadPluginsFromConf();
    }
  }

  @SneakyThrows
  private void loadPluginsFromConf() {
    for (Plugin plugin : getPluginsFromConfFiles()) {
      if (StringUtils.isNotEmpty(plugin.getClassName())) {
        className2Plugin.put(plugin.getClassName(), plugin);
      }

      if (CollectionUtils.isNotEmpty(plugin.getClassNames())) {
        plugin.getClassNames().stream().filter(StringUtils::isNotEmpty).forEach(pluginClassName -> {
          className2Plugin.put(pluginClassName, plugin);
        });

      }
      pluginName2Plugin.put(plugin.getPluginName(), plugin);
    }
    log.debug("Plugin manager's class name to plugin mapping: {}.", JSONObject.toJSONString(className2Plugin));
    log.debug("Plugin manager's plugin name to plugin mapping: {}. ", JSONObject.toJSONString(pluginName2Plugin));
  }

  @SneakyThrows
  private Stream<Path> getConfFiles() {
    URL url = new URL(directory + pluginConfDir);
    Path pluginConfPath = Paths.get(url.toURI());
    if (!Files.exists(pluginConfPath)) {
      log.warn("Cannot find plugins directory!");
      return Collections.EMPTY_LIST.stream();
    }
    Stream<Path> walk = Files.walk(pluginConfPath);

    return walk.filter((p) -> p.toString().endsWith(".json"));
  }

  List<Plugin> getPluginsFromConfFiles() {
    return getConfFiles().map((p) -> {
      try {
        return new String(Files.readAllBytes(p));
      } catch (IOException e) {
        log.warn("Get plugins error!", e);
        return (String) null;
      }
    }).filter(Objects::nonNull).map((conf) -> {
      JSONObject js = FastJsonUtil.parseObject(conf);
      String name = js.getString(PLUGIN_NAME_KEY);
      List<String> classNames = Lists.newArrayList();
      if (js.containsKey(PLUGIN_CLASS_KEY)) {
        classNames.add(js.getString(PLUGIN_CLASS_KEY));
      }
      if (js.containsKey(PLUGIN_CLASS_NAME_LIST_KEY)) {
        classNames.addAll(js.getJSONArray(PLUGIN_CLASS_NAME_LIST_KEY).toJavaList(String.class));
      }
      List<String> libs = js.getJSONArray(PLUGIN_LIB_KEY).toJavaList(String.class);
      libs = libs.stream().map(this::formatVersion).collect(Collectors.toList());
      return Plugin
          .builder()
          .pluginName(name)
          .classNames(classNames)
          .libs(libs)
          .build();
    }).collect(Collectors.toList());
  }

  private String formatVersion(String library) {
    String buildVersion = VersionHolder.getHolder()
        .getBuildVersion();
    if (!StringUtils.contains(library, "${version}")) {
      return library;
    }
    return StringUtils.replace(library, "${version}", buildVersion);
  }

  @SneakyThrows
  private List<URL> getPluginLibraries(String name) {
    List<String> libs;
    if (className2Plugin.containsKey(name)) {
      libs = className2Plugin.get(name).getLibs();
    } else if (pluginName2Plugin.containsKey(name)) {
      libs = pluginName2Plugin.get(name).getLibs();
    } else {
      if (dryRun) {
        return new ArrayList<>();
      } else {
        if (canFallback) {
          log.info("Get config plugin lib fail, we will fallback to {}.", FALL_BACK_JAR_NAME);
          libs = pluginName2Plugin.get(FALL_BACK_JAR_NAME).getLibs();
        } else {
          throw BitSailException.asBitSailException(CommonErrorCode.CONFIG_ERROR,
              String.format("The config plugin name %s is not found!", name));
        }
      }
    }
    List<URL> ret = new ArrayList<>(libs.size());
    for (String lib : libs) {
      URL url = new URL(directory + pluginLibDir + SEPARATOR + lib);
      Path myPath = Paths.get(url.toURI());
      if (!Files.exists(myPath)) {
        throw new RuntimeException("Cannot find library: " + url);
      }
      ret.add(url);
    }
    log.info("Dynamic lib is " + JSONObject.toJSONString(ret));
    return ret;
  }

  List<URL> getPluginLibs(String plugin) {
    List<URL> dynamicLib = pluginToPluginLibsMap.get(plugin);
    if (null == dynamicLib || dynamicLib.size() == 0) {
      dynamicLib = getPluginLibraries(plugin);
      pluginToPluginLibsMap.put(plugin, dynamicLib);
    }
    return dynamicLib;
  }
}