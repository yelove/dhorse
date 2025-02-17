package org.dhorse.application.service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.cli.DefaultCliRequest;
import org.apache.maven.cli.MavenCli;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.model.Activation;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.dhorse.api.enums.CodeRepoTypeEnum;
import org.dhorse.api.enums.DeploymentStatusEnum;
import org.dhorse.api.enums.DeploymentVersionStatusEnum;
import org.dhorse.api.enums.LanguageTypeEnum;
import org.dhorse.api.enums.MessageCodeEnum;
import org.dhorse.api.enums.PackageBuildTypeEnum;
import org.dhorse.api.enums.PackageFileTypeTypeEnum;
import org.dhorse.api.enums.YesOrNoEnum;
import org.dhorse.api.param.app.branch.VersionBuildParam;
import org.dhorse.api.vo.GlobalConfigAgg;
import org.dhorse.api.vo.GlobalConfigAgg.Maven;
import org.dhorse.api.vo.App;
import org.dhorse.api.vo.AppExtendJava;
import org.dhorse.infrastructure.param.DeployParam;
import org.dhorse.infrastructure.param.DeploymentDetailParam;
import org.dhorse.infrastructure.param.DeploymentVersionParam;
import org.dhorse.infrastructure.param.AppEnvParam;
import org.dhorse.infrastructure.repository.po.ClusterPO;
import org.dhorse.infrastructure.repository.po.DeploymentDetailPO;
import org.dhorse.infrastructure.repository.po.DeploymentVersionPO;
import org.dhorse.infrastructure.repository.po.AppEnvPO;
import org.dhorse.infrastructure.strategy.repo.CodeRepoStrategy;
import org.dhorse.infrastructure.strategy.repo.GitHubCodeRepoStrategy;
import org.dhorse.infrastructure.strategy.repo.GitLabCodeRepoStrategy;
import org.dhorse.infrastructure.utils.Constants;
import org.dhorse.infrastructure.utils.DeployContext;
import org.dhorse.infrastructure.utils.K8sUtils;
import org.dhorse.infrastructure.utils.LogUtils;
import org.dhorse.infrastructure.utils.ThreadLocalUtils;
import org.dhorse.infrastructure.utils.ThreadPoolUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.LogEvent;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;

/**
 * 
 * 部署基础应用服务
 * 
 * @author 天地之怪
 */
public abstract class DeployApplicationService extends ApplicationService {

	private static final Logger logger = LoggerFactory.getLogger(DeployApplicationService.class);
	
	private static final String MAVEN_REPOSITORY_ID = "customized-nexus";
	
	private static final String MAVEN_REPOSITORY_URL = "https://repo.maven.apache.org/maven2";

	protected String buildVersion(VersionBuildParam versionBuildParam) {

		DeployContext context = buildVersionContext(versionBuildParam);
		
		//异步构建
		ThreadPoolUtils.buildVersion(() -> {
			ThreadLocalUtils.putDeployContext(context);
			try {
				logger.info("Start to build version");

				// 2.下载分支代码
				if (context.getCodeRepoStrategy().downloadBranch(context)) {
					logger.info("Download branch successfully");
				} else {
					LogUtils.throwException(logger, MessageCodeEnum.DOWNLOAD_BRANCH);
				}

				// 3.打包
				if (pack(context)) {
					logger.info("Pack successfully");
				} else {
					LogUtils.throwException(logger, MessageCodeEnum.PACK);
				}

				// 4.制作镜像并上传仓库
				if(buildImage(context)) {
					logger.info("Build image successfully");
				}else {
					LogUtils.throwException(logger, MessageCodeEnum.BUILD_IMAGE);
				}
				
				updateDeploymentVersionStatus(context.getId(), DeploymentVersionStatusEnum.BUILDED_SUCCESS.getCode());
			} catch (Throwable e) {
				updateDeploymentVersionStatus(context.getId(), DeploymentVersionStatusEnum.BUILDED_FAILUR.getCode());
				logger.error("Failed to build version", e);
			} finally {
				logger.info("End to build version");
				ThreadLocalUtils.removeDeployContext();
			}
		});
		
		return context.getNameOfImage();
	}
	
	private void updateDeploymentVersionStatus(String id, Integer status) {
		DeploymentVersionParam deploymentVersionParam = new DeploymentVersionParam();
		deploymentVersionParam.setId(id);
		deploymentVersionParam.setStatus(status);
		deploymentVersionRepository.updateById(deploymentVersionParam);
	}
	
	protected boolean deploy(DeployParam deployParam) {
		//1.准备数据
		DeployContext context = checkAndBuildDeployContext(deployParam, DeploymentStatusEnum.DEPLOYING);
		
		//2.部署
		ThreadPoolUtils.deploy(() ->{
			doDeploy(context);
		});
		
		return true;
	}
	
	private boolean doDeploy(DeployContext context) {

		ThreadLocalUtils.putDeployContext(context);
		DeploymentStatusEnum deploymentStatus = DeploymentStatusEnum.DEPLOYED_FAILURE;
		
		try {
			logger.info("Start to deploy env");
			
			// 1.部署
			if (context.getClusterStrategy().createDeployment(context)) {
				logger.info("Deploy successfully");
				deploymentStatus = DeploymentStatusEnum.DEPLOYED_SUCCESS;
				
				// 2.合并分支
				try {
					if (YesOrNoEnum.YES.getCode().equals(context.getAppEnv().getRequiredMerge())
							&& !"master".equals(context.getBranchName())
							&& !"main".equals(context.getBranchName())) {
						context.getCodeRepoStrategy().mergeBranch(context);
						deploymentStatus = DeploymentStatusEnum.MERGED_SUCCESS;
					}
				}catch (Exception e) {
					deploymentStatus = DeploymentStatusEnum.MERGED_FAILURE;
					logger.error("Failed to merge branch,", e);
				}
			} else {
				deploymentStatus = DeploymentStatusEnum.DEPLOYED_FAILURE;
			}
		} catch (Throwable e) {
			logger.error("Failed to deploy", e);
		} finally {
			updateDeployStatus(context, deploymentStatus, null, new Date());
			logger.info("End to deploy");
			ThreadLocalUtils.removeDeployContext();
		}

		return true;
	}

	protected boolean rollback(DeployParam deployParam) {
		// 1.准备数据
		DeployContext context = checkAndBuildDeployContext(deployParam, DeploymentStatusEnum.ROLLBACKING);
		
		//2.回滚
		ThreadPoolUtils.deploy(() ->{
			doRollback(context);
		});
		
		return true;
	}
	
	private boolean doRollback(DeployContext context) {
		
		ThreadLocalUtils.putDeployContext(context);
		DeploymentStatusEnum rollbackStatus = DeploymentStatusEnum.ROLLBACK_FAILURE;
		
		try {
			logger.info("Start to rollback");
			// 部署
			if (context.getClusterStrategy().createDeployment(context)) {
				rollbackStatus = DeploymentStatusEnum.ROLLBACK_SUCCESS;
				logger.info("rollback successfully");
			} else {
				LogUtils.throwException(logger, MessageCodeEnum.DEPLOY);
			}
		} catch (Throwable e) {
			throw e;
		} finally {
			updateDeployStatus(context, rollbackStatus, null, new Date());
			logger.info("End to rollback");
			ThreadLocalUtils.removeDeployContext();
		}

		return true;
	}
	
	private DeployContext buildVersionContext(VersionBuildParam versionBuildParam) {
		GlobalConfigAgg globalConfig = globalConfig();
		App app = appRepository.queryWithExtendById(versionBuildParam.getAppId());
		DeployContext context = new DeployContext();
		context.setGlobalConfigAgg(globalConfig);
		context.setBranchName(versionBuildParam.getBranchName());
		context.setApp(app);
		context.setComponentConstants(componentConstants);
		context.setCodeRepoStrategy(buildCodeRepo(context.getGlobalConfigAgg().getCodeRepo().getType()));
		
		//构建版本编号
		String nameOfImage = new StringBuilder()
				.append(context.getApp().getAppName())
				.append(":")
				.append(new SimpleDateFormat(Constants.DATE_FORMAT_YYYYMMDDHHMMSS).format(new Date()))
				.toString();
		String fullNameOfImage = fullNameOfImage(context.getGlobalConfigAgg().getImageRepo(), nameOfImage);
		context.setNameOfImage(nameOfImage);
		context.setFullNameOfImage(fullNameOfImage);
		
		//同一个应用，不允许同时构建多个版本
		DeploymentVersionParam bizParam = new DeploymentVersionParam();
		bizParam.setStatus(DeploymentVersionStatusEnum.BUILDING.getCode());
		bizParam.setAppId(versionBuildParam.getAppId());
		DeploymentVersionPO deploymentVersionPO = deploymentVersionRepository.query(bizParam);
		if(deploymentVersionPO != null) {
			LogUtils.throwException(logger, MessageCodeEnum.VERSION_IS_BUILDING);
		}
		
		//新增版本记录
		bizParam = new DeploymentVersionParam();
		bizParam.setBranchName(versionBuildParam.getBranchName());
		bizParam.setVersionName(context.getNameOfImage());
		bizParam.setAppId(versionBuildParam.getAppId());
		Date now = new Date();
		bizParam.setCreationTime(now);
		String id = deploymentVersionRepository.add(bizParam);
		context.setId(id);
		String logFilePath = Constants.buildVersionLogFile(context.getComponentConstants().getLogPath(),
				now, id);
		context.setLogFilePath(logFilePath);
		
		return context;
	}

	private DeployContext buildDeployContext(DeployParam deployParam) {
		GlobalConfigAgg globalConfig = globalConfig();
		AppEnvPO appEnvPO = appEnvRepository.queryById(deployParam.getEnvId());
		App app = appRepository.queryWithExtendById(appEnvPO.getAppId());
		ClusterPO clusterPO = clusterRepository.queryById(appEnvPO.getClusterId());
		if(clusterPO == null) {
			LogUtils.throwException(logger, MessageCodeEnum.CLUSER_EXISTENCE);
		}
		DeployContext context = new DeployContext();
		context.setGlobalConfigAgg(globalConfig);
		context.setCodeRepoStrategy(buildCodeRepo(context.getGlobalConfigAgg().getCodeRepo().getType()));
		context.setCluster(clusterPO);
		context.setBranchName(deployParam.getBranchName());
		context.setApp(app);
		context.setAppEnv(appEnvPO);
		context.setComponentConstants(componentConstants);
		context.setClusterStrategy(clusterStrategy(context.getCluster().getClusterType()));
		context.setId(deployParam.getDeploymentDetailId());
		context.setStartTime(deployParam.getDeploymentStartTime());
		context.setDeploymentAppName(K8sUtils.getReplicaAppName(app.getAppName(), appEnvPO.getTag()));
		context.setNameOfImage(deployParam.getVersionName());
		String fullNameOfImage = fullNameOfImage(context.getGlobalConfigAgg().getImageRepo(), deployParam.getVersionName());
		context.setFullNameOfImage(fullNameOfImage);
		context.setFullNameOfAgentImage(fullNameOfAgentImage(context));
		String logFilePath = Constants.deploymentLogFile(context.getComponentConstants().getLogPath(),
				context.getStartTime(), context.getId());
		context.setLogFilePath(logFilePath);
		return context;
	}
	
	private DeployContext checkAndBuildDeployContext(DeployParam deployParam, DeploymentStatusEnum deploymentStatus) {
		DeployContext context = buildDeployContext(deployParam);
		// 当前环境是否存在部署中
		DeploymentDetailParam deploymentDetailParam = new DeploymentDetailParam();
		deploymentDetailParam.setEnvId(deployParam.getEnvId());
		deploymentDetailParam.setDeploymentStatuss(Arrays.asList(DeploymentStatusEnum.DEPLOYING.getCode(),
				DeploymentStatusEnum.ROLLBACKING.getCode()));
		DeploymentDetailPO deploymentDetailPO = deploymentDetailRepository.query(deploymentDetailParam);
		if (deploymentDetailPO != null) {
			LogUtils.throwException(logger, MessageCodeEnum.ENV_DEPLOYING);
		}
		updateDeployStatus(context, deploymentStatus, context.getStartTime(), null);
		return context;
	}

	private boolean pack(DeployContext context) {
		if (!LanguageTypeEnum.JAVA.getCode().equals(context.getApp().getLanguageType())) {
			logger.info("No need to pack");
			return true;
		}
		AppExtendJava appExtend = context.getApp().getAppExtend();
		if (PackageBuildTypeEnum.MAVEN.getCode().equals(appExtend.getPackageBuildType())) {
			return doMavenPack(context.getGlobalConfigAgg().getMaven(), context.getLocalPathOfBranch());
		}

		return true;
	}

	protected CodeRepoStrategy buildCodeRepo(String codeRepoType) {
		if (CodeRepoTypeEnum.GITLAB.getValue().equals(codeRepoType)) {
			return new GitLabCodeRepoStrategy();
		} else {
			return new GitHubCodeRepoStrategy();
		}
	}

	public boolean doMavenPack(Maven mavenConf, String localPathOfBranch) {
		logger.info("Start to pack using maven");
		
		String localRepoPath = componentConstants.getDataPath() + "repository";
		System.setProperty(MavenCli.LOCAL_REPO_PROPERTY, localRepoPath);
		System.setProperty(MavenCli.MULTIMODULE_PROJECT_DIRECTORY, localRepoPath);
		
		//首先使用指定的javahome
		String javaHome = null;
		if(mavenConf != null && !StringUtils.isBlank(mavenConf.getJavaHome())) {
			javaHome = mavenConf.getJavaHome();
		}else {
			javaHome = System.getenv("JAVA_HOME");
		}
		
		if(javaHome == null) {
			LogUtils.throwException(logger, MessageCodeEnum.JAVA_HOME_IS_EMPTY);
		}
		
		String javaVersion = null;
		if(mavenConf != null && !StringUtils.isBlank(mavenConf.getJavaVersion())) {
			javaVersion = mavenConf.getJavaVersion();
		}else {
			javaVersion = System.getProperty("java.version");
		}
		
		if(javaVersion == null) {
			LogUtils.throwException(logger, MessageCodeEnum.JAVA_VERSION_IS_EMPTY);
		}
		
		String[] commands = new String[] {"clean", "package", "-Dmaven.test.skip"};
		DefaultCliRequest request = new DefaultCliRequest(commands, null);
		request.setWorkingDirectory(localPathOfBranch);
		
		Repository repository = new Repository();
		repository.setId("nexus");
		repository.setName("nexus");
		repository.setUrl(mavenConf != null && StringUtils.isNotBlank(mavenConf.getMavenRepoUrl())
				? mavenConf.getMavenRepoUrl() : MAVEN_REPOSITORY_URL);
		
		RepositoryPolicy policy = new RepositoryPolicy();
		policy.setEnabled(true);
		policy.setUpdatePolicy("always");
		policy.setChecksumPolicy("fail");
		
		repository.setReleases(policy);
		repository.setSnapshots(policy);
		
		Profile profile = new Profile();
		profile.setId(MAVEN_REPOSITORY_ID);
		Activation activation = new Activation();
		activation.setActiveByDefault(true);
		activation.setJdk(javaVersion);
		profile.setActivation(activation);
		profile.setRepositories(Arrays.asList(repository));
		profile.setPluginRepositories(Arrays.asList(repository));
		
		Properties properties = new Properties();
		properties.put("java.home", javaHome);
		properties.put("java.version", javaVersion);
		properties.put("maven.compiler.source", javaVersion);
		properties.put("maven.compiler.target", javaVersion);
		properties.put("maven.compiler.compilerVersion", javaVersion);
		properties.put("project.build.sourceEncoding", "UTF-8");
		properties.put("project.reporting.outputEncoding", "UTF-8");
		profile.setProperties(properties);
		MavenExecutionRequest executionRequest = request.getRequest();
		executionRequest.setProfiles(Arrays.asList(profile));
		executionRequest.setLoggingLevel(MavenExecutionRequest.LOGGING_LEVEL_INFO);
		
		MavenCli cli = new MavenCli();
		int status = 0;
		try {
			status = cli.doMain(request);
		} catch (Exception e) {
			logger.error("Failed to maven pack", e);
			return false;
		}
		return status == 0;
	}

	public boolean buildImage(DeployContext context) {
		AppExtendJava appExtend = context.getApp().getAppExtend();
		String fullTargetPath = context.getLocalPathOfBranch();
		if(StringUtils.isBlank(appExtend.getPackageTargetPath())) {
			fullTargetPath += "target/";
		}else {
			fullTargetPath += appExtend.getPackageTargetPath();
		}
		File packageTargetPath = Paths.get(fullTargetPath).toFile();
		if (!packageTargetPath.exists()) {
			logger.error("The target path does not exsit");
			return false;
		}

		List<Path> targetFiles = new ArrayList<>();
		for (File file : packageTargetPath.listFiles()) {
			String packageFileType = PackageFileTypeTypeEnum.getByCode(appExtend.getPackageFileType()).getValue();
			if (file.getName().endsWith("." + packageFileType)) {
				File targetFile = new File(file.getParent() + "/" + context.getApp().getAppName() + "." + packageFileType);
				file.renameTo(targetFile);
				targetFiles.add(targetFile.toPath());
				break;
			}
		}

		if (targetFiles.size() == 0) {
			logger.error("The target file does not exsit");
			return false;
		} else if (targetFiles.size() > 1) {
			logger.error("Multiple target files exist");
			return false;
		}

		//连接镜像仓库5秒超时
		System.setProperty("jib.httpTimeout", "5000");
		System.setProperty("sendCredentialsOverHttp", "true");
		String fileNameWithExtension = targetFiles.get(0).toFile().getName();
		List<String> entrypoint = Arrays.asList("java", "-jar", fileNameWithExtension);
		
		try {
			RegistryImage registryImage = RegistryImage.named(context.getFullNameOfImage()).addCredential(
					context.getGlobalConfigAgg().getImageRepo().getAuthName(),
					context.getGlobalConfigAgg().getImageRepo().getAuthPassword());
			
			JibContainerBuilder jibContainerBuilder = null;
			if (StringUtils.isBlank(context.getApp().getBaseImage())) {
				jibContainerBuilder = Jib.fromScratch();
			} else {
				jibContainerBuilder = Jib.from(context.getApp().getBaseImage());
			}
			jibContainerBuilder.addLayer(targetFiles, AbsoluteUnixPath.get("/"))
				.setEntrypoint(entrypoint)
				//对于由alpine构建的镜像，使用addVolume(AbsoluteUnixPath.fromPath(Paths.get("/etc/localtime")))代码时时区才会生效。
				//但是，由于Jib不支持RUN命令，因此像RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime也无法使用，
				//不过，可以通过手动构建基础镜像来使用RUN，然后目标镜像再依赖基础镜像。
				.addEnvironmentVariable("TZ", "Asia/Shanghai")
				.containerize(Containerizer.to(registryImage)
						.setAllowInsecureRegistries(true)
						.addEventHandler(LogEvent.class, logEvent -> logger.info(logEvent.getMessage())));
		} catch (Exception e) {
			logger.error("Failed to build image", e);
			return false;
		}

		return true;
	}

	private boolean updateDeployStatus(DeployContext context, DeploymentStatusEnum status, Date startTime, Date endTime) {
		DeploymentDetailParam deploymentDetailParam = new DeploymentDetailParam();
		deploymentDetailParam.setId(context.getId());
		deploymentDetailParam.setDeploymentStatus(status.getCode());
		if(startTime != null) {
			deploymentDetailParam.setStartTime(startTime);
		}
		if(endTime != null) {
			deploymentDetailParam.setEndTime(endTime);
			AppEnvParam appEnvParam = new AppEnvParam();
			appEnvParam.setId(context.getAppEnv().getId());
			appEnvParam.setDeploymentTime(endTime);
			appEnvRepository.updateById(appEnvParam);
		}
		return deploymentDetailRepository.updateById(deploymentDetailParam);
	}
}
