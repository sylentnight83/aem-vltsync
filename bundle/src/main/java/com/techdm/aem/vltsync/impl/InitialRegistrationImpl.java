/*
 * Copyright 2017 Daniel Henrique Alves Lima
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.techdm.aem.vltsync.impl;

import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.NotFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.PropertyOption;
import org.apache.felix.scr.annotations.PropertyUnbounded;
import org.apache.felix.scr.annotations.Reference;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.framework.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import aQute.bnd.annotation.component.Deactivate;

/**
 * Creates the necessary vlt sync config files and associates a filesystem path
 * to one or more paths in the JCR (similar to 'vlt sync register' command).
 * 
 * @author Daniel Henrique Alves Lima
 *
 */
@Component(policy = ConfigurationPolicy.REQUIRE, configurationFactory = true, metatype = true, immediate = true, label = "TechDM - "
		+ "VLT Sync Initial Registration", description = "Component for initial registration of sync folders")
public class InitialRegistrationImpl {

	/* Default value for overwrite.config.files property. */
	private static final boolean DEFAULT_OVERWRITE_CONFIG_FILES = false;

	/* Default value for sync.once.expected.time property. */
	private static final long DEFAULT_SYNC_ONCE_EXPECTED_TIME = 3000;
		
	/**
	 * Value for empty sync-once. @see <a href=
	 * "http://jackrabbit.apache.org/filevault/usage.html#a.vlt-sync-config.properties">.vlt-sync-config.properties()</a>.
	 */
	protected static final String SYNC_ONCE_DISABLED = "";

	/**
	 * Value for the "auto detecting" type. If the filesystem local path is
	 * empty or contains only control files, it will automatically select
	 * JCR2FS. Otherwise, it will automatically select FS2JCR.
	 */
	protected static final String SYNC_ONCE_AUTO = "AUTO";

	/**
	 * Value for JCR2FS sync-once. @see <a href=
	 * "http://jackrabbit.apache.org/filevault/usage.html#a.vlt-sync-config.properties">.vlt-sync-config.properties(JCR2FS)</a>.
	 */
	protected static final String SYNC_ONCE_JCR2FS = "JCR2FS";

	/**
	 * Value for FS2JCR sync-once. @see <a href=
	 * "http://jackrabbit.apache.org/filevault/usage.html#a.vlt-sync-config.properties">.vlt-sync-config.properties(FS2JCR)</a>.
	 */
	protected static final String SYNC_ONCE_FS2JCR = "FS2JCR";

	@Property(label = "Filter Roots", description = "JCR paths to be added as roots in the filter file.[Required]", unbounded = PropertyUnbounded.ARRAY)
	protected static final String PROP_FILTER_ROOTS = "filter.roots";

	@Property(label = "Local Path", description = "Filesystem local path to be added as sync root.[Required]")
	protected static final String PROP_LOCAL_PATH = "local.path";

	@Property(label = "Sync Once Type", value = SYNC_ONCE_DISABLED, description = "Type of sync-once"
			+ " to perform.[Optional] [Default: ]", options = {
					@PropertyOption(name = SYNC_ONCE_DISABLED, value = ""),
					@PropertyOption(name = SYNC_ONCE_AUTO, value = "Auto detect"),
					@PropertyOption(name = SYNC_ONCE_FS2JCR, value = "Filesystem to JCR"),
					@PropertyOption(name = SYNC_ONCE_JCR2FS, value = "JCR to Filesystem") })
	protected static final String PROP_SYNC_ONCE_TYPE = "sync.once.type";

	@Property(label = "Sync Once Expected Time", longValue = DEFAULT_SYNC_ONCE_EXPECTED_TIME, description = "How many milliseconds"
			+ " a sync-once operation would take?[Optional] [Default: " + DEFAULT_SYNC_ONCE_EXPECTED_TIME + "]")
	protected static final String PROP_SYNC_ONCE_EXPECTED_TIME = "sync.once.expected.time";

	@Property(label = "Overwrite Config Files", boolValue = DEFAULT_OVERWRITE_CONFIG_FILES, description = "Overwrite the vlt sync config files"
			+ " if they already exist?[Optional] [Default: " + DEFAULT_OVERWRITE_CONFIG_FILES + "]")
	protected static final String PROP_OVERWRITE_CONFIG_FILES = "overwrite.config.files";

	@Property(value = "Local path: {" + PROP_LOCAL_PATH + "}")
	private static final String PROP_WEBCONSOLE_NAME_HINT = "webconsole.configurationFactory.nameHint";

	/* Logger instance. */
	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Reference
	private ServiceSettingsImpl serviceSettings;

	private String[] filterRoots = null;

	private File localDir = null;

	private Boolean overwriteConfigFiles = null;

	private String syncOnceType = null;

	private Boolean willSyncOnce = null;

	@Activate
	protected void activate(final Map<String, Object> props) throws ServiceException {
		logger.debug("activate(): props = {}", props);

		this.filterRoots = PropertiesUtil.toStringArray(props.get(PROP_FILTER_ROOTS), null);
		if (this.filterRoots == null) {
			throw new ServiceException(PROP_FILTER_ROOTS + " is mandatory!");
		}

		final String localDirValue = StringUtils.trim(PropertiesUtil.toString(props.get(PROP_LOCAL_PATH), null));
		if (localDirValue == null) {
			throw new ServiceException(PROP_LOCAL_PATH + " is mandatory!");
		}

		this.localDir = new File(localDirValue);
		this.overwriteConfigFiles = PropertiesUtil.toBoolean(props.get(PROP_OVERWRITE_CONFIG_FILES),
				DEFAULT_OVERWRITE_CONFIG_FILES);

		this.syncOnceType = PropertiesUtil.toString(props.get(PROP_SYNC_ONCE_TYPE), SYNC_ONCE_DISABLED);

		generateFiles();

		Long expectedSyncOnceTime = null;
		if (this.willSyncOnce) {
			expectedSyncOnceTime = PropertiesUtil.toLong(props.get(PROP_SYNC_ONCE_EXPECTED_TIME),
					DEFAULT_SYNC_ONCE_EXPECTED_TIME);
		}

		this.serviceSettings.addSyncRoot(this.localDir, expectedSyncOnceTime);
	}

	@Deactivate
	protected void deactivate() {
		logger.debug("deactivate()");
		if (this.localDir != null) {
			this.serviceSettings.removeSyncRoot(this.localDir);
		}

		this.filterRoots = null;
		this.localDir = null;
		this.overwriteConfigFiles = null;
		this.syncOnceType = null;
		this.willSyncOnce = null;
	}

	private void generateFiles() throws IllegalStateException {
		logger.debug("generateFiles()");

		try {
			this.localDir.mkdirs();

			logger.debug("generateFiles(): Generating vlt sync config files at {}", this.localDir);
			generateVltSyncConfigFiles();

		} catch (IOException e) {
			logger.error("generateFiles()", e);
			throw new IllegalStateException(e);
		}
	}

	private void generateVltSyncConfigFiles() throws IOException {
		generateWorkspaceFilterFile();
		generateConfigPropertyFile();
	}

	private void generateConfigPropertyFile() throws IOException {
		final String configPropertyFilename = ".vlt-sync-config.properties";
		final List<String> existentConfig = getExistentPaths(configPropertyFilename);

		/*
		 * Don't overwrite the file if it already exists. Allowing the user to
		 * create or change .vlt-sync-config.properties guarantees a finer
		 * control over VTL sync service behavior.
		 */
		if (existentConfig.isEmpty() || this.overwriteConfigFiles) {
			logger.debug("generateConfigPropertyFile(): writing {} at {}", configPropertyFilename, this.localDir);

			this.willSyncOnce = Boolean.TRUE;
			final File[] localDirContents = getLocalDirContents();
			final String syncOnce;

			if (SYNC_ONCE_AUTO.equals(this.syncOnceType)) {
				syncOnce = (localDirContents == null || localDirContents.length == 0) ? SYNC_ONCE_JCR2FS
						: SYNC_ONCE_FS2JCR;
			} else {
				syncOnce = this.syncOnceType;
			}

			PrintWriter writer = null;
			try {
				writer = new PrintWriter(new FileWriter(new File(this.localDir, configPropertyFilename)));
				writer.println("disabled=false");
				writer.println("sync-once=" + syncOnce);
			} finally {
				IOUtils.closeQuietly(writer);
			}
		} else {
			if (logger.isDebugEnabled()) {
				logger.debug("generateConfigPropertyFile(): {} already contains {}!", this.localDir, existentConfig);
			}
		}

		if (this.willSyncOnce == null) {
			/* Check if the existent file has a sync-once instruction. */
			Properties props = new Properties();
			props.load(new FileReader(new File(this.localDir, configPropertyFilename)));
			String syncOnceValue = props.getProperty("sync-once");
			this.willSyncOnce = StringUtils.isNotEmpty(syncOnceValue);
		}
	}

	private void generateWorkspaceFilterFile() throws IOException {
		final String workspaceFilterFilename = ".vlt-sync-filter.xml";
		final String defaultWorkspaceFilterFilename = "META-INF/vault/filter.xml";

		final List<String> existentFilter = getExistentPaths(workspaceFilterFilename);
		final List<String> existentDefaultFilter = getExistentPaths(defaultWorkspaceFilterFilename,
				"../" + defaultWorkspaceFilterFilename);

		/*-
		 * To make this component compatible with other VLT commands, such
		 * as checkout and commit, we should not create a sync filter if a
		 * default filter already exists! 
		 * Reference: http://jackrabbit.apache.org/filevault/usage.html#a.vlt-sync-filter.xml
		 * 
		 * To guarantee a finer control to the user, don't overwrite an
		 * already created/configured file!
		 * 
		 */
		if (existentDefaultFilter.isEmpty() && (existentFilter.isEmpty() || this.overwriteConfigFiles)) {
			logger.debug("generateWorkspaceFilterFile(): writing {} at {}", workspaceFilterFilename, this.localDir);

			PrintWriter writer = null;
			try {
				writer = new PrintWriter(new FileWriter(new File(localDir, workspaceFilterFilename)));
				writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
				writer.println("<workspaceFilter version=\"1.0\">");
				for (String path : this.filterRoots) {
					writer.println("\t<filter root=\"" + path + "\"/>");
				}
				writer.println("</workspaceFilter>");
			} finally {
				IOUtils.closeQuietly(writer);
			}
		} else {
			if (logger.isDebugEnabled()) {
				final List<String> filters = new ArrayList<String>(existentFilter);
				filters.addAll(existentDefaultFilter);

				logger.debug("generateWorkspaceFilterFile(): {} already contains {}!", this.localDir, filters);
			}
		}
	}

	private File[] getLocalDirContents() {
		FileFilter fileFilter = new NotFileFilter(new RegexFileFilter("^.vlt-sync.+$"));
		File[] localDirContents = this.localDir.listFiles(fileFilter);

		return localDirContents;
	}

	private List<String> getExistentPaths(final String... relativePaths) {
		List<String> existentPaths = new ArrayList<String>();

		for (String path : relativePaths) {
			final File file = new File(this.localDir, path);
			if (file.exists()) {
				existentPaths.add(path);
			}
		}

		return existentPaths;
	}

}
