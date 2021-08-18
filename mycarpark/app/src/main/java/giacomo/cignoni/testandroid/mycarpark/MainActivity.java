package giacomo.cignoni.testandroid.mycarpark;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    static float DEFAULT_MAP_ZOOM = 17f;

    private CoordinatorLayout coordinatorLayout;
    private BottomSheetBehavior bottomSheetBehavior;
    private FloatingActionButton fabAddLocation;
    private TextView textViewCurrCar;
    private LinearLayout hiddenTopBar;
    private CardView cardTopBar;
    private ImageButton topExpandArrow;

    // register the permissions callback
    private ActivityResultLauncher<String> requestPermissionLauncher;

    private LocationManager locationManager;
    private AlarmManager alarmManager;


    private RecyclerView rvPark;
    private ParkRVAdapter parkAdapter;
    private RecyclerView rvCar;
    private CarRVAdapter carAdapter;
    private DBViewModel DBViewModel;

    private GoogleMap map;
    //map with <parkId, MarkerOptions> couples, used for deleting or modifying markers.
    //Item is present in markersMap => item is present in markerOptionsMap in viewModel,
    //but the opposite is not always true.
    private Map<Long, Marker> markersMap;

    private Bitmap bitmapCurrentMarker;
    private Bitmap bitmapOldMarker;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //initializes requestPermissionLauncher with callback
        requestPermissionLauncher = this.registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                            if (isGranted) {
                                // permission is granted
                                Log.d("mytag", "addNewLocation:  permission granted after dialog");
                                //start again addMapCurrPosition
                                addMapCurrPosition();

                            } else {
                                // Explain to the user that the feature is unavailable
                                Toast.makeText(this.getApplicationContext(), "location permession not granted after dialog", Toast.LENGTH_SHORT).show();

                            }
                });

        //generates bitmaps for map markers
        bitmapCurrentMarker = generateBitmapFromVector(R.drawable.ic_car_current_24_vect);
        bitmapOldMarker = generateBitmapFromVector(R.drawable.ic_car_old_24_vect);

        //base coordinator layout
        coordinatorLayout = findViewById(R.id.coordinator_layout_base);

        locationManager = new LocationManager(this);

        //initializes alarmManager
        alarmManager = new AlarmManager(this);

        //init bottom sheet
        initBottomSheet();

        //init parks recycler
        initParkRecyclerView();

        //init top bar
        initTopBar();

        //init viewModel
        DBViewModel = new ViewModelProvider(this).get(DBViewModel.class);

        DBViewModel.getAllCars().observe(this, cars -> {
            // Update the cached copy of the cars in the adapter
            carAdapter.submitList(cars);
        });


        MainActivity ma = this;

        Car currentCar;
        //field currentCar has been preserved in the viewModel
        if ((currentCar = DBViewModel.getCurrentCar()) != null) {
            // Update top textview with car name
            textViewCurrCar.setText(currentCar.getName());
            //initialize parks for current car
            DBViewModel.updateParksByCurrentCarId(currentCar.getCarId());
            //init parks observer
            DBViewModel.getCurrentCarParks().observe(ma, parks -> {
                // Update the cached copy of the parks in the adapter
                parkAdapter.submitList(parks);

                /*//sets collapsed height of bottom sheet as first RV element height
                this.bottomSheetBehavior.setPeekHeight(
                        rvPark.getLayoutManager().findViewByPosition(0).getHeight());

                 */
            });
        }

        //get current car from DB
        else if (DBViewModel.getLiveInitialCurrentCar() != null) {
            //starting currCar observer
            DBViewModel.getLiveInitialCurrentCar().observe(ma, car -> {
                if (car != null) {
                    //initializes currentCar in viewModel
                    DBViewModel.setCurrentCar(car);
                    // Update top textview with car name
                    textViewCurrCar.setText(car.getName());
                    //initialize parks for current car
                    DBViewModel.updateParksByCurrentCarId(car.getCarId());
                    //init parks observer
                    DBViewModel.getCurrentCarParks().observe(ma, parks -> {
                        // Update the cached copy of the parks in the adapter
                        parkAdapter.submitList(parks);
                    });

                    //remove observers for LiveData of initialCurrentCar
                    DBViewModel.getLiveInitialCurrentCar().removeObservers(ma);
                    //DBViewModel.resetInitialCurrentCar();
                }
            });
        }



        //init new location button
        initFabAddLocation();

        initMap();
    }

    public void switchCar(Car newSelectedCar) {
        // Update top textview with car name
        textViewCurrCar.setText(newSelectedCar.getName());

        //remove observers from oldparks liveData
        DBViewModel.getCurrentCarParks().removeObservers(this);
        //updates parks liveData with parks for new current car
        DBViewModel.updateParksByCurrentCarId(newSelectedCar.getCarId());
        //set observer for new parks livedata
        DBViewModel.getCurrentCarParks().observe(this, parks -> {
            // Update the cached copy of the parks in the adapter
            parkAdapter.submitList(parks);
        });

        //set isCurrent as true for newly selected car
        DBViewModel.updateIsCurrentCar(newSelectedCar.getCarId(), true);
        //set previous curr car isCurrent as false
        DBViewModel.updateIsCurrentCar(DBViewModel.getCurrentCar().getCarId(), false);

        //sets currentCar in viewModel as the selected car
        DBViewModel.setCurrentCar(newSelectedCar);

        //resets map markers
        this.removeAllMarkers();

        //closes top bar
        this.toggleExpandTopBar();
    }

    public void addNewCar(EditText editAddCar) {
        String carName = editAddCar.getText().toString();
        Log.d("mytag", "new car: " + carName);
        if (!carName.trim().equals("")) {
            //new car set as current
            Car c = new Car(carName, false);
            DBViewModel.insertCar(c);

        } else {
            //TODO: make toast or other
            Log.d("mytag", "invalid car name");
            Snackbar.make(coordinatorLayout, "use a non-void car name",
                    BaseTransientBottomBar.LENGTH_SHORT).show();
        }
        //reset input text
        editAddCar.setText("");
        //close keyboard
        InputMethodManager imm = (InputMethodManager) this.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(editAddCar.getWindowToken(), 0);
        //remove focus form edit text
        editAddCar.clearFocus();
    }

    public void initTopBar() {
        hiddenTopBar = findViewById(R.id.layout_hidden_top_bar);
        cardTopBar = findViewById(R.id.card_top_bar);
        topExpandArrow = findViewById(R.id.button_expand_arrow);
        textViewCurrCar = findViewById(R.id.textview_curr_car);

        //init add car
        EditText editAddCar = findViewById(R.id.editText_add_car);
        ImageButton buttonAddCar = findViewById(R.id.button_add_car);

        //onclick listener for + button
        buttonAddCar.setOnClickListener(v -> {
            addNewCar(editAddCar);
        });

        //listener for done button on keyboard
        editAddCar.setOnEditorActionListener((v, actionId, event) -> {
            addNewCar(editAddCar);
            return true;
        });

        //init cars recycler
        initCarRecyclerView();

        topExpandArrow.setOnClickListener(v -> this.toggleExpandTopBar());
    }

    public void toggleExpandTopBar() {
        Log.d("mytag", "expandedArrow: cliccato ");
        //TODO: close keyboard if open

        // If the CardView is already expanded, set its visibility
        //  to gone and change the expand less icon to expand more.
        if (hiddenTopBar.getVisibility() == View.VISIBLE) {

            // The transition of the hiddenView is carried out
            //  by the TransitionManager class.
            // Here we use an object of the AutoTransition
            // Class to create a default transition.
            TransitionManager.beginDelayedTransition(cardTopBar, new AutoTransition());
            hiddenTopBar.setVisibility(View.GONE);
            topExpandArrow.setImageResource(R.drawable.ic_baseline_expand_more_24);
        }

        // If the CardView is not expanded, set its visibility
        // to visible and change the expand more icon to expand less.
        else {

            TransitionManager.beginDelayedTransition(cardTopBar, new AutoTransition());
            hiddenTopBar.setVisibility(View.VISIBLE);
            topExpandArrow.setImageResource(R.drawable.ic_baseline_expand_less_24);
        }
    }

    public void initMap() {
        //init markers map
        markersMap = new HashMap<>();

        MapFragment mapFragment = (MapFragment) getFragmentManager().findFragmentById(R.id.map_main);
        mapFragment.getMapAsync(googleMap -> {
            this.map = googleMap;


            // set map type
            googleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
            //disable zoom controls
            googleMap.getUiSettings().setZoomControlsEnabled(false);
            //sets top padding for map controls
            googleMap.setPadding(0, 256, 0, 0);

            this.addMapCurrPosition();

            //sets longonclick listener to add new current park on long press
            googleMap.setOnMapLongClickListener(latLng -> {
                locationManager.reverseGeocode(latLng.latitude, latLng.longitude);
            });

            googleMap.getUiSettings().setMapToolbarEnabled(true);


            //restores all markers previously preserved in the viewModel
            for (Map.Entry<Long, MarkerOptions> entry : DBViewModel.getMarkerOptionsMap().entrySet()) {
                //restore marker to map if not already restored (if restored the corresponding value
                //of the entry in the Marker objects map is not null)
                if (this.markersMap.get(entry.getKey()) == null) {
                    this.addParkMarker(entry.getValue(), entry.getKey());
                }
            }
        });
    }

    /*
    Checks location permissions to add current location to map
     */
    public void addMapCurrPosition() {
        //checks and requests location permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.d("mytag", "addNewLocation: no permission location");
            requestPermissionLauncher.launch(
                    Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }
        this.map.setMyLocationEnabled(true);
    }

    public DBViewModel getDBViewModel(){
        return DBViewModel;
    }

    public AlarmManager getAlarmManager() {
        return this.alarmManager;
    }

    public long getCurrentCarId() {
        //return currentCarId;
        return DBViewModel.getCurrentCar().getCarId();
    }

    public void addNewLocation() {
        Log.d("mytag", "addNewLocation: cliccato ");
        locationManager.setCurrentLocation();
    }

    public void insertPark(ParkAddress addr) {
        //get current time in millis
        long currentTime = Calendar.getInstance().getTimeInMillis();
        //updates current markers present on map to old markers
        this.setPreviusCurrMarkersToOldMarkers();

        //create new park
        Park p = new Park(addr, this.getCurrentCarId(), currentTime);
        //insert in database
        DBViewModel.insertPark(p);

        //haptic feedback of confirmation
        this.fabAddLocation.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    }

    /*
    Deletes park from DB and its marker on the map if present
     */
    public void deletePark(Park p) {
        DBViewModel.deletePark(p);
        removeMarker(p.getParkId());
    }

    public void initFabAddLocation(){
        fabAddLocation = findViewById(R.id.fab_add_location);
        fabAddLocation.setOnClickListener(v -> addNewLocation());
    }

    public void initParkRecyclerView(){
        rvPark = findViewById(R.id.recyclerview_park);

        parkAdapter = new ParkRVAdapter(new ParkRVAdapter.ParkDiff(), this);
        rvPark.setAdapter(parkAdapter);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        rvPark.setLayoutManager(llm);
    }

    public void initCarRecyclerView(){
        rvCar = findViewById(R.id.recyclerview_car);

        carAdapter = new CarRVAdapter(new CarRVAdapter.CarDiff(), this);
        rvCar.setAdapter(carAdapter);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        rvCar.setLayoutManager(llm);
    }

    private void initBottomSheet() {
        // get the bottom sheet view
        LinearLayout llBottomSheet = findViewById(R.id.bottom_sheet);
        // init the bottom sheet behavior
        bottomSheetBehavior = BottomSheetBehavior.from(llBottomSheet);
        // change the state of the bottom sheet
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
    }

    public BottomSheetBehavior getBottomSheetBehavior() {
        return bottomSheetBehavior;
    }

    /*
    Generates MarkerOptions for current park, adds it to markerOptionsMap and calls addParkMarker method
     */
    public void addCurrParkMarker(Park park) {
        if(markersMap.get(park.getParkId()) == null) {
            //create MarkerOptions
            LatLng position = new LatLng(park.getAddress().getLatitude(), park.getAddress().getLongitude());
            MarkerOptions m = new MarkerOptions()
                    .position(position);
            BitmapDescriptor icon = BitmapDescriptorFactory.fromBitmap(this.bitmapCurrentMarker);
            m.icon(icon);
            //adds MarkerOptions to map data structure to preserve it
            DBViewModel.putMarkerOptions(park.getParkId(), m);
            //adds isCurrent info to map
            DBViewModel.putMarkerIsCurr(park.getParkId(), Boolean.TRUE);

            addParkMarker(m, park.getParkId());
        }
    }

    /*
    Generates MarkerOptions for old park, adds it to markerOptionsMap and calls addParkMarker method
    */
    public void addOldParkMarker(Park park) {
        if(markersMap.get(park.getParkId()) == null) {
            //create MarkerOptions
            LatLng position = new LatLng(park.getAddress().getLatitude(), park.getAddress().getLongitude());
            MarkerOptions m = new MarkerOptions()
                    .position(position);
            BitmapDescriptor icon = BitmapDescriptorFactory.fromBitmap(this.bitmapOldMarker);
            m.icon(icon);
            //adds MarkerOptions to map data structure to preserve it
            DBViewModel.putMarkerOptions(park.getParkId(), m);
            //adds isCurrentMarker info to map
            DBViewModel.putMarkerIsCurr(park.getParkId(), Boolean.FALSE);

            addParkMarker(m, park.getParkId());
        }
        else {
            //if new marker is not created, focus on existing marker
            centerCameraOnMarker(park.getParkId());
        }
    }

    /*
    Add marker to GoogleMap from MarkerOptions and generated Marker Object to markersMap data structure.
    Called both for current and old park markers
     */
    public void addParkMarker(MarkerOptions m, long parkId) {
        Marker marker;
        //adds marker to GoogleMap if already loaded
        if (this.map != null){
            marker = this.map.addMarker(m);

            //adds marker to Map for later deletion or modification
            this.markersMap.put(parkId, marker);
        }

        //map camera animation on marker focus on marker
       this.centerCameraOnMarker(parkId);

    }

    /*
    Centers map camera on marker corresponding to a parkId if present on the map
     */
    public void centerCameraOnMarker(long parkId) {
        Marker marker;
        if ((marker = this.markersMap.get(parkId)) != null) {
            this.map.moveCamera(CameraUpdateFactory.newLatLngZoom(marker.getPosition(), DEFAULT_MAP_ZOOM));

            //closes bottomSheet to show map
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        }
    }

    /*
   Returns if a marker refers to a current park, based on the value of a map in the viewModel
    */
    public boolean isCurrentParkMarker(long parkId) {
        if (DBViewModel.getMarkerIsCurr(parkId)) return true;
        else return false;
    }

    /*
   Updates all current park markers to old park markers.
    */
    public void setPreviusCurrMarkersToOldMarkers() {
        for(Map.Entry<Long, Marker> entry : markersMap.entrySet()) {
            if (isCurrentParkMarker(entry.getKey())) {
                //updates value in isCurrentMarker map of viewModel to false
                DBViewModel.putMarkerIsCurr(entry.getKey(), false);
                //update markerOptions
                MarkerOptions m = DBViewModel.getMarkerOptions(entry.getKey());
                BitmapDescriptor icon = BitmapDescriptorFactory.fromBitmap(this.bitmapOldMarker);
                m.icon(icon);
                //adds MarkerOptions to map data structure to preserve it
                DBViewModel.putMarkerOptions(entry.getKey(), m);
                //updates Marker
                entry.getValue().setIcon(icon);
            }
        }
    }

    /*
    Remove all markers from the GoogleMap and the maps used to keep track of markers
     */
    public void removeAllMarkers() {
        for(Marker m : this.markersMap.values()) {
            m.remove();
            Log.d("mytag", "removeAllMarkers: marker with tag: "+m.getTag());
        }
        //reset Markers, MarkerOptions and isCurrentMarker maps
        markersMap.clear();
        DBViewModel.resetMarkerOptionsMap();
        DBViewModel.resetMarkerIsCurrMap();
    }

    /*
    Remove the marker associated to a single park from GoogleMap and data structures
     */
    public void removeMarker(long parkId) {
        Marker marker;
        if((marker = this.markersMap.get(parkId)) != null) marker.remove();
        //remove values with parkId key from Markers, MarkerOptions and isCurrentMarker maps
        this.markersMap.remove(parkId);
        DBViewModel.removeMarkerOptions(parkId);
        DBViewModel.removeMarkerIsCurr(parkId);
    }

    /*
    Set endtime for current park, so it becomes an old park
     */
    public void dismissPark(Park park){
        //get current time in millis
        long endTime = Calendar.getInstance().getTimeInMillis();
        DBViewModel.dismissPark(park, endTime);

        //change current park marker to old park marker
        this.setPreviusCurrMarkersToOldMarkers();
    }

    /*
    Returns string representing date in the format passed as an argument of millis from epoch
     */
    public static String getDate(long milliSeconds, String dateFormat) {
        // Create a DateFormatter object for displaying date in specified format
        SimpleDateFormat formatter = new SimpleDateFormat(dateFormat);

        // Create a calendar object that will convert the date and time value in milliseconds to date
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(milliSeconds);
        return formatter.format(calendar.getTime());
    }

    /*
    Generates bitmap from resource id of drawable vector.
    From https://stackoverflow.com/questions/33696488/getting-bitmap-from-vector-drawable
     */
    public Bitmap generateBitmapFromVector(int resourceId) {
        Drawable drawable = getDrawable(resourceId);
        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    /*
    Unfocus editText and closes keyboard when clicking outside editText
    https://stackoverflow.com/questions/4828636/edittext-clear-focus-on-touch-outside
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View v = getCurrentFocus();
            if (v instanceof EditText) {
                Rect outRect = new Rect();
                v.getGlobalVisibleRect(outRect);
                if (!outRect.contains((int)event.getRawX(), (int)event.getRawY())) {
                    v.clearFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                }
            }
        }
        return super.dispatchTouchEvent( event );
    }
}