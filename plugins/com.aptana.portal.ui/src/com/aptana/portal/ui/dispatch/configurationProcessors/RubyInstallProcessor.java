package com.aptana.portal.ui.dispatch.configurationProcessors;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.progress.UIJob;

import com.aptana.configurations.processor.AbstractConfigurationProcessor;
import com.aptana.configurations.processor.ConfigurationStatus;
import com.aptana.core.util.InputStreamGobbler;
import com.aptana.ide.core.io.LockUtils;
import com.aptana.ide.core.io.downloader.DownloadManager;
import com.aptana.portal.ui.PortalUIPlugin;

/**
 * A Ruby install processor.<br>
 * This class is in charge of downloading and installing Ruby and DevKit for Windows operating systems.<br>
 * Note: In case we decide to support something similar for MacOSX and Linux, this processor would probably need
 * delegators set up.
 * 
 * @author Shalom Gibly <sgibly@aptana.com>
 */
public class RubyInstallProcessor extends AbstractConfigurationProcessor
{
	protected static final String RUBY_DEFAULT_INSTALL_PATH = "C:\\Ruby19"; //$NON-NLS-1$
	protected static final String DEVKIT_FSTAB_PATH = "\\devkit\\msys\\1.0.11\\etc\\fstab"; //$NON-NLS-1$
	protected static final String DEVKIT_FSTAB_LOCATION_PREFIX = "C:/Ruby/"; //$NON-NLS-1$
	protected static final String APTANA_PROPERTIES_FILE_NAME = ".aptana"; //$NON-NLS-1$
	private static final String WINDOWS_7ZIP_EXECUTABLE = "$os$/7za.exe"; //$NON-NLS-1$
	// The process return code for a Ruby installer cancel.
	private static final int RUBY_INSTALLER_PROCESS_CANCEL = 5;
	private static boolean installationInProgress;
	private String[] downloadedPaths;
	private String[] urls;

	/**
	 * Configure Ruby on the machine.<br>
	 * The configuration will grab the installer and the DevKit from the given attributes. We expects the first
	 * attribute to contain the Ruby installer link, and the second attribute to contain the DevKit 7z package.
	 * 
	 * @param attributes
	 *            An array of strings holding the rubyinstaller and the devkit URLs.
	 * @see com.aptana.configurations.processor.AbstractConfigurationProcessor#configure(org.eclipse.core.runtime.IProgressMonitor,
	 *      java.lang.Object)
	 */
	@Override
	public ConfigurationStatus configure(IProgressMonitor progressMonitor, Object attributes)
	{
		// Get a Class lock to avoid multiple installations at the same time even with multiple instances of this
		// RubyInstallProcessor
		synchronized (this.getClass())
		{
			if (installationInProgress)
			{
				return configurationStatus;
			}
			installationInProgress = true;
		}
		try
		{
			configurationStatus.removeAttribute(CONFIG_ATTR);
			clearErrorAttributes();
			if (attributes == null || !(attributes instanceof Object[]))
			{
				applyErrorAttributes(Messages.RubyInstallProcessor_missingRubyInstallURLs);
				PortalUIPlugin.logError(new Exception(Messages.RubyInstallProcessor_missingRubyInstallURLs));
				return configurationStatus;
			}
			Object[] attrArray = (Object[]) attributes;
			if (attrArray.length != 2)
			{
				// structure error
				applyErrorAttributes(Messages.RubyInstallProcessor_wrongNumberOfRubyInstallLinks + attrArray.length);
				PortalUIPlugin.logError(new Exception(Messages.RubyInstallProcessor_wrongNumberOfRubyInstallLinks
						+ attrArray.length));
				return configurationStatus;
			}

			// Start the installation...
			configurationStatus.setStatus(ConfigurationStatus.PROCESSING);
			IStatus status = download(attrArray, progressMonitor);
			if (status.isOK())
			{
				status = install(progressMonitor);
			}
			switch (status.getCode())
			{
				case IStatus.OK:
				case IStatus.INFO:
				case IStatus.WARNING:
					configurationStatus.setStatus(ConfigurationStatus.OK);
					break;
				case IStatus.ERROR:
					configurationStatus.setStatus(ConfigurationStatus.ERROR);
					break;
				case IStatus.CANCEL:
					configurationStatus.setStatus(ConfigurationStatus.INCOMPLETE);
					break;
				default:
					configurationStatus.setStatus(ConfigurationStatus.UNKNOWN);
			}
			return configurationStatus;
		}
		finally
		{
			synchronized (this.getClass())
			{
				installationInProgress = false;
			}
		}
	}

	/**
	 * Download the remote content and store it the temp directory.
	 * 
	 * @param URLs
	 * @param progressMonitor
	 */
	private IStatus download(Object[] URLs, IProgressMonitor progressMonitor)
	{
		downloadedPaths = null;
		DownloadManager downloadManager = new DownloadManager();
		urls = new String[URLs.length];
		List<URL> urlsList = new ArrayList<URL>(URLs.length);
		for (int i = 0; i < URLs.length; i++)
		{
			try
			{
				Object o = URLs[i];
				urls[i] = o.toString();
				urlsList.add(new URL(urls[i]));
			}
			catch (MalformedURLException mue)
			{
				PortalUIPlugin.logError(mue);
			}
		}
		try
		{
			downloadManager.addURLs(urlsList);
			IStatus status = downloadManager.start(progressMonitor);
			if (status.isOK())
			{
				downloadedPaths = downloadManager.getContentsLocations();
			}
			return status;
		}
		catch (Exception e)
		{
			PortalUIPlugin.logError(e);
		}
		return Status.CANCEL_STATUS;
	}

	/**
	 * Install Ruby and DevKit.
	 * 
	 * @param progressMonitor
	 * @return
	 */
	private IStatus install(IProgressMonitor progressMonitor)
	{
		if (downloadedPaths == null || downloadedPaths[0] == null || downloadedPaths[1] == null)
		{
			String failureMessge = Messages.RubyInstallProcessor_couldNotLocateRubyinstaller;
			if (downloadedPaths != null && downloadedPaths[0] != null)
			{
				failureMessge = Messages.RubyInstallProcessor_couldNotLocateDevKit;
			}
			displayMessageInUIThread(Messages.RubyInstallProcessor_installationErrorTitle,
					Messages.RubyInstallProcessor_failedToInstallRuby + ' ' + failureMessge);
			return new Status(IStatus.ERROR, PortalUIPlugin.PLUGIN_ID,
					Messages.RubyInstallProcessor_failedToInstallRuby + ' ' + failureMessge);
		}
		SubMonitor subMonitor = SubMonitor.convert(progressMonitor,
				Messages.RubyInstallProcessor_installerProgressInfo, IProgressMonitor.UNKNOWN);
		try
		{
			subMonitor.beginTask(Messages.RubyInstallProcessor_installingRubyTask, IProgressMonitor.UNKNOWN);
			final String[] installDir = new String[1];
			Job installRubyDialog = new UIJob("Ruby installer options") //$NON-NLS-1$
			{
				@Override
				public IStatus runInUIThread(IProgressMonitor monitor)
				{
					RubyInstallerOptionsDialog dialog = new RubyInstallerOptionsDialog();
					if (dialog.open() == Window.OK)
					{
						installDir[0] = dialog.getInstallDir();
						return Status.OK_STATUS;
					}
					else
					{
						return Status.CANCEL_STATUS;
					}
				}
			};
			installRubyDialog.schedule();
			try
			{
				installRubyDialog.join();
			}
			catch (InterruptedException e)
			{
			}
			IStatus result = installRubyDialog.getResult();
			if (!result.isOK())
			{
				return result;
			}

			IStatus status = installRuby(installDir[0]);
			if (!status.isOK())
			{
				if (status.getSeverity() != IStatus.CANCEL)
				{
					displayMessageInUIThread(Messages.RubyInstallProcessor_installationErrorTitle, status.getMessage());
				}
				return status;
			}
			PortalUIPlugin.logInfo(
					"Successfully installed Ruby into " + installDir[0] + ". Starting to install DevKit...", null); //$NON-NLS-1$ //$NON-NLS-2$
			// Ruby was installed successfully. Now we need to extract DevKit into the Ruby dir and change its
			// configurations to match the installation location.
			status = installDevKit(installDir[0]);
			if (!status.isOK())
			{
				displayMessageInUIThread(Messages.RubyInstallProcessor_installationErrorTitle, status.getMessage());
				return status;
			}
			finalizeInstallation(installDir[0]);
			PortalUIPlugin.logInfo(
					"Successfully installed DevKit into " + installDir[0] + ". Ruby installation completed.", null); //$NON-NLS-1$ //$NON-NLS-2$
			return Status.OK_STATUS;
		}
		catch (Exception e)
		{
			PortalUIPlugin.logError("Error while installing Ruby", e); //$NON-NLS-1$
			return new Status(IStatus.ERROR, PortalUIPlugin.PLUGIN_ID,
					Messages.RubyInstallProcessor_errorWhileInstallingRuby);
		}
		finally
		{
			subMonitor.done();
		}
	}

	/**
	 * Run the Ruby installer and install Ruby into the given directory.
	 * 
	 * @param installDir
	 * @return The status of this installation
	 */
	protected IStatus installRuby(final String installDir)
	{
		Job job = new Job(Messages.RubyInstallProcessor_rubyInstallerJobName)
		{
			@Override
			protected IStatus run(IProgressMonitor monitor)
			{
				try
				{
					SubMonitor subMonitor = SubMonitor.convert(monitor, IProgressMonitor.UNKNOWN);
					subMonitor
							.beginTask(Messages.RubyInstallProcessor_installingRubyTaskName, IProgressMonitor.UNKNOWN);
					PortalUIPlugin.logInfo("Installing Ruby into " + installDir, null); //$NON-NLS-1$

					// Try to get a file lock first, before running the process. This file was just downloaded, so there
					// is a chance it's still being held by the OS or by the downloader.
					IStatus fileLockStatus = LockUtils.waitForLockRelease(downloadedPaths[0], 10000L);
					if (!fileLockStatus.isOK())
					{
						return new Status(IStatus.ERROR, PortalUIPlugin.PLUGIN_ID,
								Messages.RubyInstallProcessor_installationError_generic);
					}

					ProcessBuilder processBuilder = new ProcessBuilder(downloadedPaths[0],
							"/silent", "/dir=\"" + installDir + "\"", "/tasks=\"modpath\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					Process process = processBuilder.start();
					int res = process.waitFor();
					if (res == RUBY_INSTALLER_PROCESS_CANCEL)
					{
						PortalUIPlugin.logInfo("Ruby installation cancelled", null); //$NON-NLS-1$
						return Status.CANCEL_STATUS;
					}
					if (res != 0)
					{
						// We had an error while installing
						PortalUIPlugin
								.logError(
										"Failed to install Ruby. The ruby installer process returned a termination code of " + res, null); //$NON-NLS-1$
						return new Status(IStatus.ERROR, PortalUIPlugin.PLUGIN_ID, res,
								Messages.RubyInstallProcessor_installationErrorMessage, null);
					}
					else if (!new File(installDir).exists())
					{
						// Just to be sure that we got everything in place
						PortalUIPlugin.logError(
								"Failed to install Ruby. The " + installDir + " directory was not created", null); //$NON-NLS-1$ //$NON-NLS-2$
						return new Status(IStatus.ERROR, PortalUIPlugin.PLUGIN_ID, res,
								Messages.RubyInstallProcessor_installationError_installDirMissing, null);
					}
					return Status.OK_STATUS;
				}
				catch (Exception e)
				{
					PortalUIPlugin.logError(e);
					return new Status(IStatus.ERROR, PortalUIPlugin.PLUGIN_ID,
							Messages.RubyInstallProcessor_installationError_generic, e);
				}
				finally
				{
					monitor.done();
				}
			}
		};
		// Give it a little delay, just in case the downloader still holds on to the rubyinstaller file.
		job.schedule(3000);
		try
		{
			job.join();
		}
		catch (InterruptedException e)
		{
			PortalUIPlugin.logError(e);
			return Status.CANCEL_STATUS;
		}
		return job.getResult();
	}

	/**
	 * Extract the downloaded DevKit into the install dir and configure it to work.<br>
	 * At this stage, we assume that the install dir and the DevKit package have been verified and valid!
	 * 
	 * @param installDir
	 * @return The result status of the installation
	 * @throws Exception
	 */
	protected IStatus installDevKit(final String installDir)
	{
		Job job = new Job(Messages.RubyInstallProcessor_installingDevKitJobName)
		{
			@Override
			protected IStatus run(IProgressMonitor monitor)
			{
				try
				{
					SubMonitor subMonitor = SubMonitor.convert(monitor, 1000);
					subMonitor.beginTask(Messages.RubyInstallProcessor_extractingDevKitTaskName + installDir, 900);
					// We get a folder status first, before unzipping into the folder. This folder was just created,
					// so there is a chance it's still being held by the OS or by the Ruby installer.
					IStatus folderStatus = LockUtils.waitForFolderAccess(installDir, 10000);
					if (!folderStatus.isOK())
					{
						PortalUIPlugin.getDefault().getLog().log(folderStatus);
						return new Status(IStatus.ERROR, PortalUIPlugin.PLUGIN_ID,
								Messages.RubyInstallProcessor_failedToinstallDevKit);
					}
					// DevKit arrives as a 7zip package, so we use a specific Windows decoder to extract it.
					// This extraction process follows the instructions at:
					// http://wiki.github.com/oneclick/rubyinstaller/development-kit
					extract(downloadedPaths[1], installDir);
					subMonitor.worked(900);

					subMonitor.beginTask(Messages.RubyInstallProcessor_updatingDevKitTaskName, 100);
					// Update the /devkit/msys/1.0.11/etc/fstab with the Ruby installation path
					File fstab = new File(installDir, DEVKIT_FSTAB_PATH);
					StringBuilder builder = new StringBuilder();
					// read the content of the original file and update the Ruby location to the selected one. Then,
					// save the new content and override the file.
					String pathReplacement = installDir.replaceAll("\\\\", "/"); //$NON-NLS-1$ //$NON-NLS-2$
					if (!pathReplacement.endsWith("/")) //$NON-NLS-1$
					{
						pathReplacement = pathReplacement + '/';
					}
					String newLine = System.getProperty("line.separator", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
					BufferedReader reader = new BufferedReader(new FileReader(fstab));
					String line = null;
					while ((line = reader.readLine()) != null)
					{
						line = line.replaceAll(DEVKIT_FSTAB_LOCATION_PREFIX, pathReplacement);
						builder.append(line);
						builder.append(newLine);
					}
					reader.close();
					// Now save the modified content into the same file
					FileWriter writer = new FileWriter(fstab);
					writer.write(builder.toString());
					writer.flush();
					writer.close();
					subMonitor.worked(100);
				}
				catch (Throwable t)
				{
					PortalUIPlugin.logError("Failed to install DevKit", t); //$NON-NLS-1$
					return new Status(IStatus.ERROR, PortalUIPlugin.PLUGIN_ID,
							Messages.RubyInstallProcessor_failedToinstallDevKit, t);
				}
				finally
				{
					monitor.done();
				}
				return Status.OK_STATUS;
			}
		};
		job.schedule(500);
		try
		{
			job.join();
		}
		catch (InterruptedException e)
		{
			return Status.CANCEL_STATUS;
		}
		return job.getResult();
	}

	/**
	 * Finalize the installation by placing a .aptana file in the installed directory, specifying some properties.
	 * 
	 * @param installDir
	 */
	protected void finalizeInstallation(String installDir)
	{
		File propertiesFile = new File(installDir, APTANA_PROPERTIES_FILE_NAME);
		Properties properties = new Properties();
		properties.put("ruby_install", urls[0]); //$NON-NLS-1$
		properties.put("devkit_install", urls[1]); //$NON-NLS-1$
		FileOutputStream fileOutputStream = null;
		try
		{
			fileOutputStream = new FileOutputStream(propertiesFile);
			properties.store(fileOutputStream, Messages.RubyInstallProcessor_aptanaFileComment);
		}
		catch (IOException e)
		{
			PortalUIPlugin.logError(e);
		}
		finally
		{
			if (fileOutputStream != null)
			{
				try
				{
					fileOutputStream.flush();
					fileOutputStream.close();
				}
				catch (IOException e)
				{
				}
			}
		}
	}

	/**
	 * Extract the given zip file into the target folder.
	 * 
	 * @param zipFile
	 * @param targetFolder
	 * @return The status of that extraction result.
	 */
	public static IStatus extract(String zipFile, String targetFolder)
	{
		IStatus errorStatus = new Status(IStatus.ERROR, PortalUIPlugin.PLUGIN_ID,
				Messages.RubyInstallProcessor_unableToExtractDevKit);
		if (zipFile == null || targetFolder == null)
		{
			PortalUIPlugin.logError("Undefined zip file or target folder", new Exception()); //$NON-NLS-1$
			return errorStatus;
		}
		IPath zipExecutable = getBundlePath(WINDOWS_7ZIP_EXECUTABLE);
		File destinationFolder = new File(targetFolder);
		if (!destinationFolder.exists() && !destinationFolder.mkdirs())
		{
			PortalUIPlugin.logError("Failed to create destination directory " + destinationFolder, new Exception()); //$NON-NLS-1$
			return errorStatus;
		}
		ProcessBuilder processBuilder = new ProcessBuilder(zipExecutable.toOSString(), "x", //$NON-NLS-1$
				"-o" + targetFolder, //$NON-NLS-1$
				"-y", //$NON-NLS-1$
				zipFile);
		processBuilder.directory(destinationFolder);
		processBuilder.redirectErrorStream(true);
		String output = null;
		try
		{
			Process process = processBuilder.start();
			InputStreamGobbler errorGobbler = new InputStreamGobbler(process.getErrorStream(), "\n", null); //$NON-NLS-1$
			InputStreamGobbler outputGobbler = new InputStreamGobbler(process.getInputStream(), "\n", null); //$NON-NLS-1$
			outputGobbler.start();
			errorGobbler.start();
			process.waitFor();
			outputGobbler.interrupt();
			errorGobbler.interrupt();
			outputGobbler.join();
			errorGobbler.join();
			output = outputGobbler.getResult();
			String errors = errorGobbler.getResult();
			int exitVal = process.exitValue();
			if (exitVal == 0)
			{
				return Status.OK_STATUS;
			}
			else
			{
				PortalUIPlugin
						.logError(
								"DevKit extraction failed. The process returned " + exitVal, new Exception("Process output:\n" + errors)); //$NON-NLS-1$ //$NON-NLS-2$
				return errorStatus;
			}
		}
		catch (Exception e)
		{
			PortalUIPlugin.logError(e);
			return errorStatus;
		}
		finally
		{
			if (output != null)
			{
				PortalUIPlugin.logInfo(output, null);
			}
		}
	}

	/*
	 * Returns an IPath from the given portable string.
	 */
	private static IPath getBundlePath(String path)
	{
		URL url = FileLocator.find(PortalUIPlugin.getDefault().getBundle(), Path.fromPortableString(path), null);
		if (url != null)
		{
			try
			{
				url = FileLocator.toFileURL(url);
				File file = new File(url.getPath());
				if (file.exists())
				{
					return Path.fromOSString(file.getAbsolutePath());
				}
			}
			catch (IOException e)
			{
				PortalUIPlugin.logError(e);
			}
		}
		return null;
	}

	private void displayMessageInUIThread(final String title, final String message)
	{
		Display.getDefault().syncExec(new Runnable()
		{
			@Override
			public void run()
			{
				MessageDialog.openInformation(null, title, message);
			}
		});
	}

	/*
	 * (non-Javadoc)
	 * @see com.aptana.configurations.processor.AbstractConfigurationProcessor#computeStatus(org.eclipse.core.runtime.
	 * IProgressMonitor, java.lang.Object)
	 */
	@Override
	public ConfigurationStatus computeStatus(IProgressMonitor progressMonitor, Object attributes)
	{
		// This one does nothing. We compute the Ruby status in the generic VersionsConfigurationProcessor
		return configurationStatus;
	}

	private class RubyInstallerOptionsDialog extends TitleAreaDialog
	{
		private Text path;
		private String installDir;

		public RubyInstallerOptionsDialog()
		{
			super(Display.getDefault().getActiveShell());
			setTitleImage(PortalUIPlugin.getDefault().getImageRegistry().get(PortalUIPlugin.RUBY));
			setBlockOnOpen(true);
			setHelpAvailable(false);
			installDir = RUBY_DEFAULT_INSTALL_PATH;
		}

		@Override
		protected void configureShell(Shell newShell)
		{
			super.configureShell(newShell);
			newShell.setText(Messages.RubyInstallProcessor_installerShellTitle);
		}

		/**
		 * Returns the installation dir selected in the text field.
		 * 
		 * @return the installation directory
		 */
		public String getInstallDir()
		{
			return installDir;
		}

		@Override
		protected Control createDialogArea(Composite parent)
		{
			Composite composite = (Composite) super.createDialogArea(parent);
			// Create a inner composite so we can control the margins
			Composite inner = new Composite(composite, SWT.NONE);
			inner.setLayoutData(new GridData(GridData.FILL_BOTH));
			GridLayout layout = new GridLayout();
			layout.marginLeft = 4;
			layout.marginRight = 4;
			layout.marginTop = 4;
			layout.marginBottom = 4;
			inner.setLayout(layout);

			Group group = new Group(inner, SWT.NONE);
			group.setText(Messages.RubyInstallProcessor_installerGroupTitle);
			group.setLayout(new GridLayout());
			GridData layoutData = new GridData(GridData.FILL_BOTH);
			group.setLayoutData(layoutData);

			Label l = new Label(group, SWT.WRAP);
			l.setText(Messages.RubyInstallProcessor_installerMessage);
			Composite installLocation = new Composite(group, SWT.NONE);
			installLocation.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			installLocation.setLayout(new GridLayout(2, false));
			path = new Text(installLocation, SWT.SINGLE | SWT.BORDER);
			path.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
			path.setText(installDir);
			path.addKeyListener(new KeyListener()
			{
				@Override
				public void keyReleased(org.eclipse.swt.events.KeyEvent e)
				{
					installDir = path.getText();
				}

				@Override
				public void keyPressed(org.eclipse.swt.events.KeyEvent e)
				{
					installDir = path.getText();
				}
			});
			Button browse = new Button(installLocation, SWT.PUSH);
			browse.setText(Messages.RubyInstallProcessor_browse);
			browse.addSelectionListener(new SelectionAdapter()
			{
				@Override
				public void widgetSelected(SelectionEvent e)
				{
					DirectoryDialog dirDialog = new DirectoryDialog(getParentShell());
					String dir = dirDialog.open();
					if (dir != null)
					{
						path.setText(dir);
						installDir = dir;
					}
				}
			});
			setTitle(Messages.RubyInstallProcessor_installerTitle);
			return composite;
		}
	}
}
