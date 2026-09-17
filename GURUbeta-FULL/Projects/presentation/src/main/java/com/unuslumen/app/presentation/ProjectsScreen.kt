@file:OptIn(ExperimentalLayoutApi::class)

package com.unuslumen.app.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.unuslumen.app.domain.model.Project
import com.unuslumen.app.domain.model.ProjectSummary
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.LiquidFloatingActionButton
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.snackbar.showSnackbar
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun ProjectsScreen(
    navController: NavHostController,
    viewModel: ProjectsViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var projectToEdit by remember { mutableStateOf<Project?>(null) }
    val liquidState = rememberLiquidState()
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            guruAppBar(
                title = stringResource(R.string.projects),
                actions = {
                    IconButton(onClick = { /* TODO: Search */ }) {
                        Icon(Icons.Default.Search, contentDescription = "Search projects")
                    }
                }
            )
        },
        floatingActionButton = {
            LiquidFloatingActionButton(
                onClick = { showCreateDialog = true },
                iconPainter = androidx.compose.ui.res.painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.add_project),
                liquidState = liquidState
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .liquefiable(liquidState)
        ) {
            if (uiState.projects.isEmpty()) {
                NoProjectsMessage()
            } else {
                ProjectsGrid(
                    projects = uiState.projects,
                    onProjectClick = { project ->
                        navController.navigate(Screen.ProjectDetailScreen(project.id))
                    },
                    onProjectLongPress = { project ->
                        projectToEdit = project
                    }
                )
            }
        }
    }
    
    // Create/Edit Project Dialog
    if (showCreateDialog || projectToEdit != null) {
        ProjectDialog(
            project = projectToEdit,
            onDismiss = { 
                showCreateDialog = false
                projectToEdit = null
            },
            onSave = { title, description, promptOverlay, color, icon ->
                if (projectToEdit != null) {
                    viewModel.onEvent(ProjectsEvent.UpdateProject(
                        projectToEdit!!.copy(
                            title = title,
                            description = description,
                            promptOverlay = promptOverlay,
                            color = color,
                            icon = icon
                        )
                    ))
                } else {
                    viewModel.onEvent(ProjectsEvent.CreateProject(
                        title = title,
                        description = description,
                        promptOverlay = promptOverlay,
                        color = color,
                        icon = icon
                    ))
                }
                showCreateDialog = false
                projectToEdit = null
            }
        )
    }
}

@Composable
fun NoProjectsMessage() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Projects Yet",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create a project to start working with Guru on specific tasks",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

@Composable
fun ProjectsGrid(
    projects: List<Project>,
    onProjectClick: (Project) -> Unit,
    onProjectLongPress: (Project) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(projects, key = { it.id }) { project ->
            ProjectCard(
                project = project,
                onClick = { onProjectClick(project) },
                onLongPress = { onProjectLongPress(project) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = try {
                Color(android.graphics.Color.parseColor(project.color))
            } catch (e: Exception) {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getProjectIcon(project.icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                // Menu
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                    )
                }
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            showMenu = false
                            onLongPress()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            showMenu = false
                            // TODO: Delete project
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Title
            Text(
                text = project.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            // Description
            if (project.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = project.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Stats
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatChip(
                    label = "${project.messageCount} msgs",
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                )
                StatChip(
                    label = "${project.documentCount} docs",
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun StatChip(
    label: String,
    color: Color
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

@Composable
fun getProjectIcon(iconName: String): ImageVector {
    return when (iconName.lowercase()) {
        "code", "coding", "development" -> Icons.Default.Code
        "work", "business" -> Icons.Default.Work
        "description", "document", "writing" -> Icons.Default.Description
        else -> Icons.Default.Folder
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDialog(
    project: Project?,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, promptOverlay: String, color: String, icon: String) -> Unit
) {
    var title by remember { mutableStateOf(project?.title ?: "") }
    var description by remember { mutableStateOf(project?.description ?: "") }
    var promptOverlay by remember { mutableStateOf(project?.promptOverlay ?: "") }
    var selectedColor by remember { mutableStateOf(project?.color ?: "#6366f1") }
    var selectedIcon by remember { mutableStateOf(project?.icon ?: "folder") }
    
    val colors = listOf(
        "#6366f1", // Indigo
        "#8b5cf6", // Purple
        "#ec4899", // Pink
        "#ef4444", // Red
        "#f97316", // Orange
        "#eab308", // Yellow
        "#22c55e", // Green
        "#14b8a6", // Teal
        "#0ea5e9", // Sky
        "#64748b"  // Slate
    )
    
    val icons = listOf("folder", "code", "work", "description")
    
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (project == null) "Create Project" else "Edit Project") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = promptOverlay,
                    onValueChange = { promptOverlay = it },
                    label = { Text("Custom Instructions (Optional)") },
                    placeholder = { Text("E.g., 'You are helping with wedding planning...'") },
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Color picker
                Text("Color", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    colors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(color)))
                                .clickable { selectedColor = color },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedColor == color) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
                
                // Icon picker
                Text("Icon", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    icons.forEach { icon ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selectedIcon == icon) 
                                        MaterialTheme.colorScheme.primary
                                    else 
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { selectedIcon = icon },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = getProjectIcon(icon),
                                contentDescription = null,
                                tint = if (selectedIcon == icon) 
                                    MaterialTheme.colorScheme.onPrimary 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onSave(title, description, promptOverlay, selectedColor, selectedIcon)
                    }
                }
            ) {
                Text(if (project == null) "Create" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}