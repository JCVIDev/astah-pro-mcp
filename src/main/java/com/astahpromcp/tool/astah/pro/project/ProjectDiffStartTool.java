package com.astahpromcp.tool.astah.pro.project;

import com.astahpromcp.tool.ToolDefinition;
import com.astahpromcp.tool.ToolSupport;
import com.astahpromcp.tool.astah.pro.AstahToolProvider;
import com.astahpromcp.tool.astah.pro.project.inputdto.DiffProjectsDTO;
import com.astahpromcp.tool.astah.pro.project.outputdto.DiffStartDTO;
import com.astahpromcp.tool.projectdiff.ProjectDiffJob;
import com.astahpromcp.tool.projectdiff.ProjectDiffJobs;
import com.astahpromcp.tool.projectdiff.ProjectDiffRunner;
import com.astahpromcp.tool.projectdiff.ProjectDiffStore;
import com.astahpromcp.tool.projectdiff.export.OpenProjectSnapshot;
import com.astahpromcp.tool.projectdiff.export.SubprocessXmiExporter;
import com.astahpromcp.tool.projectdiff.outputdto.ProjectSideDTO;
import com.change_vision.jude.api.inf.project.ProjectAccessor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

// Tool for starting a comparison of two versions of a project, whose result is fetched with get_proj_diff_result
@Slf4j
public class ProjectDiffStartTool extends AstahToolProvider {
    
    private final ProjectAccessor projectAccessor;
    private final ProjectDiffJobs projectDiffJobs;
    private final CurrentProjectXmiExporter currentProjectXmiExporter;
    
    public ProjectDiffStartTool(ProjectAccessor projectAccessor, ProjectDiffJobs projectDiffJobs) {
        this.projectAccessor = projectAccessor;
        this.projectDiffJobs = projectDiffJobs;
        this.currentProjectXmiExporter = new CurrentProjectXmiExporter(projectAccessor);
    }
    
    @Override
    protected List<ToolDefinition> createTools() {
        return List.of(
            ToolSupport.toolDefinitionReturningDto(
                "diff_proj",
                "Start comparing two versions of an Astah project in the background, and return a 'diffId' at once. MCP client (you) MUST call the project diff guide tool before calling this tool function, to understand which projects to pass, how to start the comparison, and how to get and read its result.",
                this::diffProject,
                DiffProjectsDTO.class,
                DiffStartDTO.class)
        );
    }
    
    private DiffStartDTO diffProject(DiffProjectsDTO param) throws Exception {
        
        if (!param.isProjectDiffGuideCalled()) {
            throw new IllegalArgumentException("The comparison was not started, because 'isProjectDiffGuideCalled' is false. Call the project diff guide tool first, and then call this tool function again with 'isProjectDiffGuideCalled' set to true.");
        }
        
        String requestId = param.requestId() == null ? "" : param.requestId().strip();
        Path basePath = projectPathOf(param.baseProjectPath());
        boolean openTarget = param.targetProjectPath() == null || param.targetProjectPath().isBlank();
        Path targetPath = openTarget ? null : projectPathOf(param.targetProjectPath());
        String inputKey = keyOf(basePath) + "\n" + (openTarget ? "(the open project)" : keyOf(targetPath));
        
        // A repeated request gets the comparison it started, whatever has become of its files or of the open project since
        ProjectDiffJob earlier = projectDiffJobs.earlier(requestId, inputKey);
        if (earlier != null) {
            return startOf(earlier);
        }
        
        requireProjectFile(param.baseProjectPath());
        if (openTarget) {
            if (!projectAccessor.hasProject()) {
                throw new IllegalArgumentException("No project is open in Astah to compare with. Set 'targetProjectPath' to compare two project files.");
            }
        } else {
            requireProjectFile(param.targetProjectPath());
        }
        
        // A request with the same requestId may have got in since it was looked for, in which case its comparison is returned and nothing is prepared
        ProjectDiffJobs.Admission admission = projectDiffJobs.startComparison(requestId, inputKey, newJob -> prepare(newJob, basePath, targetPath));
        ProjectDiffJob job = admission.job();
        if (admission.created()) {
            log.info("Started the comparison {}: {} -> {}", job.diffId(), basePath, targetPath == null ? "the open project" : targetPath);
        }
        return startOf(job);
    }
    
    private static DiffStartDTO startOf(ProjectDiffJob job) {
        return new DiffStartDTO(job.diffId(), job.status(), job.base(), job.target());
    }
    
    // Copy the project files, capture the open project if it is the target, and return the rest for the background.
    // This runs while the Astah API lock is held; the rest, which never touches the Astah API, does not (ProjectDiffRunner).
    private Runnable prepare(ProjectDiffJob job, Path basePath, Path targetPath) throws Exception {
        SubprocessXmiExporter.Plan plan = SubprocessXmiExporter.plan(System.getProperty("java.home"), projectAccessor.getAstahInstallPath(), XmiExportMain.class);
        SubprocessXmiExporter.checkCommandLine(plan);
        
        Path baseDirectory = ProjectDiffStore.baseOf(job.directory());
        Path targetDirectory = ProjectDiffStore.targetOf(job.directory());
        ProjectSideDTO baseSide = ProjectDiffRunner.fileSide(basePath);
        ProjectSideDTO targetSide = targetPath == null ? null : ProjectDiffRunner.fileSide(targetPath);
        job.recordSides(baseSide, targetSide);
        
        ProjectDiffStore.copy(basePath, baseDirectory.resolve(SubprocessXmiExporter.PROJECT_FILE_NAME), job::stopRequested);
        
        OpenProjectSnapshot snapshot = null;
        if (targetPath != null) {
            ProjectDiffStore.copy(targetPath, targetDirectory.resolve(SubprocessXmiExporter.PROJECT_FILE_NAME), job::stopRequested);
        } else {
            Files.createDirectories(targetDirectory);
            snapshot = currentProjectXmiExporter.capture(targetDirectory.resolve(SubprocessXmiExporter.XMI_FILE_NAME));
            targetSide = ProjectDiffRunner.openProjectSide(snapshot);
            job.recordSides(baseSide, targetSide);
            
            projectDiffJobs.retryPendingDeletions();  // Astah has sometimes let go of the files an earlier failed export held open once an export succeeds
        }
        
        return new ProjectDiffRunner(projectDiffJobs, job, plan,
                new ProjectDiffRunner.Side(baseSide, baseDirectory, null),
                new ProjectDiffRunner.Side(targetSide, targetDirectory, snapshot));
    }
    
    // The path of a project file that a request names, as the requests are told apart by. Whether the file is there is checked only for a new comparison (requireProjectFile).
    private static Path projectPathOf(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("The specified file path is empty.");
        }
        ProjectAccessorTool.requireAbsolutePath(filePath);
        return Path.of(filePath).normalize();
    }
    
    // A path as the requests are told apart by
    static String keyOf(Path path) {
        return keyOf(path, File.separatorChar == '\\');
    }
    
    static String keyOf(Path path, boolean caseInsensitive) {
        return caseInsensitive ? path.toString().toLowerCase(Locale.ROOT) : path.toString();
    }
    
    private static void requireProjectFile(String filePath) {
        if (!new File(filePath).isFile()) {
            throw new IllegalArgumentException("The specified project file does not exist: " + filePath);
        }
    }
}
