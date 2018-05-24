package network.omega.ui.resource;

import com.jfoenix.controls.JFXTextField;
import cy.agorise.graphenej.Asset;
import cy.agorise.graphenej.api.ListAssets;
import cy.agorise.graphenej.api.android.NodeConnection;
import cy.agorise.graphenej.errors.RepeatedRequestIdException;
import cy.agorise.graphenej.interfaces.NodeErrorListener;
import cy.agorise.graphenej.interfaces.WitnessResponseListener;
import cy.agorise.graphenej.models.BaseResponse;
import cy.agorise.graphenej.models.WitnessResponse;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Point2D;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import network.omega.ui.main.MainController;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;

import javax.swing.filechooser.FileSystemView;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ResourceController implements Initializable, ControllerHooks {

    @FXML
    private StackPane rootPane;

    @FXML
    private JFXTextField searchResourceType;

    @FXML
    private ListView searchResultResources;

    @FXML
    private TextArea resourceDescription;

    @FXML
    private Label selectDiskLabel;

    @FXML
    private ListView disksList;

    @FXML
    private Button saveButton;


    private Tooltip errTooltip = new Tooltip();

    private Stage stage = null;

    public MainController mc;

    protected static NodeConnection nodeConnection;
    public HashMap<String, Asset> resourceTypesFetched;
    protected List<Asset> filteredResourceTypesBySearchedText = new ArrayList<>();

    @Override
    public void setStage(Stage stage) {
        this.stage = stage;
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        selectDiskLabel.setText("Select the disk\nto store virtual\nmachine at");
        //update the available disks list to select only one
        updateDisksList(disksList);

        //configure error tooltip
        errTooltip.addEventHandler(MouseEvent.MOUSE_CLICKED, new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent event) {
                errTooltip.hide();
            }
        });
        Image image = new Image(
                getClass().getResourceAsStream("/resources/if_stock_error-next_94134.png")
        );
        errTooltip.setGraphic(new ImageView(image));
    }

    public Path getRootPath(FileStore fs) throws IOException {
        Path media = Paths.get("/media");
        if (media.isAbsolute() && Files.exists(media)) { // Linux
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(media)) {
                for (Path p : stream) {
                    if (Files.getFileStore(p).equals(fs)) {
                        return p;
                    }
                }
            }
        } else { // Windows
            IOException ex = null;
            for (Path p : FileSystems.getDefault().getRootDirectories()) {
                try {
                    if (Files.getFileStore(p).equals(fs)) {
                        return p;
                    }
                } catch (IOException e) {
                    ex = e;
                }
            }
            if (ex != null) {
                throw ex;
            }
        }
        return null;
    }

    private List<FileStore> currentDisksList = new ArrayList<>();

    private void updateDisksList(ListView disksList) {
        // returns pathnames for files and directory
//        File[] paths;
//        FileSystemView fsv = FileSystemView.getFileSystemView();
//        paths = File.listRoots();

        // for each pathname in pathname array
        currentDisksList.clear();
        List<String> disks = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            // prints file and directory paths
            try {
                FileStore currentStore = Files.getFileStore(root);
                long currentStoreFreeSpaceInGB = currentStore.getUsableSpace() / 1024l / 1024l / 1024l;
                if (currentStoreFreeSpaceInGB > 10) {
                    String diskName = "";
                    if (currentStore.name().contentEquals("")) {
                        diskName = getRootPath(currentStore).toFile().getAbsolutePath();
                    } else {
                        diskName = currentStore.name() +
                                " - " + getRootPath(currentStore).toFile().getAbsolutePath();
                    }
                    disks.add(diskName + " - " + currentStoreFreeSpaceInGB + " GB Free ");
                    currentDisksList.add(currentStore);
                    //System.out.println("Drive Name: " + path);
                    //System.out.println("Description: " + fsv.getSystemTypeDescription(path));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }


        }
        disksList.setItems(FXCollections.observableList(disks));
    }

    public ResourceDescription getResourceDescriptionForCurrentlySelectedType() {
        String selectedAsset = (String) searchResultResources.getSelectionModel().getSelectedItem();
        if (selectedAsset != null) {
            for (Asset a : filteredResourceTypesBySearchedText) {
                ResourceDescription rd = new ResourceDescription();
                rd.parse(a);
                if (selectedAsset.contains(rd.name)) {
                    return rd;
                }
            }
        }
        return null;
    }

    public FileStore getCurrentlySelectedDisk() {
        int selectedIndex = disksList.getSelectionModel().getSelectedIndex();
        if (selectedIndex != -1) {
            FileStore currentStore = currentDisksList.get(selectedIndex);
            if (currentStore != null) {
                return currentStore;
            } else {
                return null;
            }
        }
        return null;
    }

    public String diskSizeIsValid(ResourceDescription jsonReq, FileStore currentlySelectedDisk) {
        try {
            if (jsonReq.freeDiskRequired <= (currentlySelectedDisk.getUsableSpace() / 1024.0f / 1024.0f / 1024.0f)) {
                System.out.println("Disk: " + jsonReq.freeDiskRequired + " <= "
                        +(currentlySelectedDisk.getUsableSpace() / 1024.0f / 1024.0f / 1024.0f));
                return null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return jsonReq.freeDiskRequired + " GB of free disk needed";
    }

    public String ramSizeIsValid(ResourceDescription jsonReq, HardwareAbstractionLayer hal) {
        float availableMemoryGB = hal.getMemory().getAvailable() / 1024.0f / 1024.0f / 1024f;
        if (jsonReq.freeRamRequired <= availableMemoryGB) {
            System.out.println("RAM: " + jsonReq.freeRamRequired + " <= " +availableMemoryGB);
            return null;
        }
        return jsonReq.freeRamRequired + " GB of free RAM needed";
    }

    public String cpuCoresNumIsValid(ResourceDescription jsonReq, HardwareAbstractionLayer hal) {
        long logicalCores =  hal.getProcessor().getLogicalProcessorCount();
        if (jsonReq.minCPUCoresRequired <= logicalCores) {
            System.out.println("CPU: " + jsonReq.minCPUCoresRequired + " <= " +logicalCores);
            return null;
        }
        return "Minimum of " + jsonReq.minCPUCoresRequired + " CPU cores needed";
    }


    public String isValid() {
        StringBuilder result = new StringBuilder();
        Boolean isValid = false;
        //get selected resource type and json of that type
        //searchResultResources
        ResourceDescription rd = getResourceDescriptionForCurrentlySelectedType();

        //validate selection made to resource type json data - disk size, ram and core number.
        SystemInfo si = new SystemInfo();
        HardwareAbstractionLayer hal = si.getHardware();

        //do validation
        if(rd == null){
            result.append("Select the resource type\n");
        }
        FileStore currentlySelectedDisk = getCurrentlySelectedDisk();
        if(currentlySelectedDisk == null){
            result.append("Select the disk\n");
        }
        if(currentlySelectedDisk != null && rd != null) {
            //disk check
            String diskSizeIsValid = diskSizeIsValid(rd, currentlySelectedDisk);
            if(diskSizeIsValid != null){
                result.append(diskSizeIsValid);
            }
            //ram check
            String ramSizeIsValid = ramSizeIsValid(rd, hal);
            if(ramSizeIsValid!=null){
                result.append(ramSizeIsValid);
            }
            //cpu check
            String cpuCoresNumIsValid = cpuCoresNumIsValid(rd, hal);
            if(cpuCoresNumIsValid!=null){
                result.append(cpuCoresNumIsValid);
            }
            if (diskSizeIsValid == null && ramSizeIsValid == null && cpuCoresNumIsValid == null) {
                return null;
            }
        }
        return result.toString();
    }

    public void addResource(ActionEvent actionEvent) {

        String isValidMsg = isValid();

        if (isValidMsg == null) {
            //update resources table
            System.out.println("Valid.");
        } else {
            //show invalid message details
            if(stage!=null) {
                System.out.println(isValidMsg);
                //create a tooltip
                errTooltip.setText(isValidMsg);
                saveButton.setTooltip(errTooltip);
                errTooltip.setAutoHide(true);
                Point2D p = saveButton.localToScene(0.0, 0.0);
                errTooltip.show(stage, p.getX()
                        + saveButton.getScene().getX() + saveButton.getScene().getWindow().getX(), p.getY()
                        + saveButton.getScene().getY() + saveButton.getScene().getWindow().getY());
            }
        }
    }

    @Override
    public void close() {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        mc.openedAddResourceForms--;
        stage.close();
    }

    @Override
    public void toFront() {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        //stage.show();
        stage.toFront();
    }

    public void cancel(ActionEvent actionEvent) {
        close();
    }

    public void updateTypesList() {
        String currentSearchText = searchResourceType.getText();
        Platform.runLater(() -> {
            //create filtered set of types from fetched collection
            filteredResourceTypesBySearchedText.clear();
            for (Asset currentType : resourceTypesFetched.values()) {
                if (currentType.getAssetOptions().getDescription().toLowerCase().contains(currentSearchText.toLowerCase())
                        ||
                        currentType.getSymbol().toLowerCase().contains(currentSearchText.toLowerCase())) {
                    filteredResourceTypesBySearchedText.add(currentType);
                }
            }

            //update fx component list with filtered collection

            List<String> justResourceTypesNames = new ArrayList<>();
            for (Asset a : filteredResourceTypesBySearchedText) {
                ResourceDescription rd = new ResourceDescription();
                rd.parse(a);
                justResourceTypesNames.add(rd.name);
            }
            searchResultResources.setItems(FXCollections.observableList(justResourceTypesNames));
            searchResultResources.setOnMouseClicked(new EventHandler<MouseEvent>() {
                @Override
                public void handle(MouseEvent event) {
                    ResourceDescription rd = getResourceDescriptionForCurrentlySelectedType();
                    if (rd != null) {
                        resourceDescription.setText(rd.toString());
                    }
                }
            });
        });
    }

    private long MILLIS_TO_PASS_BETWEEN_KEYS_STROKES = 500L;
    private long inputLocked = 0L;
    private ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1);

    public void onSearchResourceInputChange(KeyEvent keyEvent) {
        //String currentSearchText = ((JFXTextField) keyEvent.getSource()).getText() + keyEvent.getCharacter();
        //don't run if specific time is not passed since last key stroke
        if (System.currentTimeMillis() >= inputLocked) {
            inputLocked = System.currentTimeMillis() + MILLIS_TO_PASS_BETWEEN_KEYS_STROKES;
            //System.out.println(currentSearchText);
            //fetchResourceTypes(currentSearchText, 100);

            //start thread that will run once just after input unlocked
            ex.schedule(() -> updateTypesList(),
                    MILLIS_TO_PASS_BETWEEN_KEYS_STROKES, TimeUnit.MILLISECONDS);
        }
    }

    private NodeErrorListener mErrorListener = new NodeErrorListener() {
        @Override
        public void onError(BaseResponse.Error error) {
            System.out.println("onError");
        }
    };

}