package com.example.huyng.nutrisnap;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NavUtils;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.example.huyng.nutrisnap.classifier.Classifier;
import com.example.huyng.nutrisnap.classifier.TensorFlowImageClassifier;
import com.example.huyng.nutrisnap.database.Entry;
import com.example.huyng.nutrisnap.database.EntryRepository;
import com.example.huyng.nutrisnap.database.Food;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;

import static android.content.ContentValues.TAG;

public class ResultActivity extends AppCompatActivity
        implements AddFoodDialogFragment.DialogListener{
    private static final int INPUT_SIZE = 299;
    private static final int IMAGE_MEAN = 128;
    private static final float IMAGE_STD = 128.0f;
    private static final String INPUT_NAME = "Mul:0";
    private static final String OUTPUT_NAME = "final_result";

    private static final String MODEL_FILE = "file:///android_asset/rounded_graph.pb";
    private static final String LABEL_FILE = "file:///android_asset/retrained_labels.txt";
    private static final String JSON_FILE = "food_data.json";

    private static final boolean MAINTAIN_ASPECT = true;

    private Bitmap croppedBitmap;

    RecyclerAdapter adapter;
    ArrayList<Food> foodInfos;

    JSONObject jsonReader;
    private EntryRepository entryRepository;
    private String imageName = null;

    public Context getActivityContext() {
        return this;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);

        // Display food image
        Bundle extras = getIntent().getExtras();
        Uri imageUri = null;

        if (extras != null) {
            //  Get image URI from intent
            if (extras.containsKey("selectedImage"))
                imageUri = Uri.parse(extras.getString("selectedImage"));
            else if (extras.containsKey("capturedImage"))
                imageUri = Uri.parse("file://" + extras.getString("capturedImage"));

            try {
                InputStream image_stream = getContentResolver().openInputStream(imageUri);
                Bitmap bitmap= BitmapFactory.decodeStream(image_stream );

                // Crop bitmap for TensorFlow Classifier
                croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Config.ARGB_8888);
                Matrix transformMatrix = getTransformationMatrix(
                                bitmap.getWidth(), bitmap.getHeight(),
                                INPUT_SIZE, INPUT_SIZE,
                                0, MAINTAIN_ASPECT);
                final Canvas canvas = new Canvas(croppedBitmap);
                canvas.drawBitmap(bitmap, transformMatrix, null);

                // Classify image in background
                new ClassifyImageTask().execute(croppedBitmap);

                // Set up RecyclerView to display results
                RecyclerView rv = (RecyclerView)findViewById(R.id.rv);
                LinearLayoutManager llm = new LinearLayoutManager(this);
                rv.setLayoutManager(llm);

                // Initialize RecyclerAdapter
                foodInfos = new ArrayList<Food>();
                adapter = new RecyclerAdapter(this, bitmap, true, foodInfos);
                rv.setAdapter(adapter);

                // Initialize json reader to parse food data
                InputStream is = getAssets().open(JSON_FILE);
                int size = is.available();
                byte[] buffer = new byte[size];
                is.read(buffer);
                is.close();
                String jsonString = new String(buffer, "UTF-8");
                jsonReader = new JSONObject(jsonString);

                // Initialize database
                entryRepository = new EntryRepository(this);

            } catch (Exception ex) {
                Toast.makeText(this, "There was a problem", Toast.LENGTH_SHORT).show();
                Log.e(TAG, ex.toString());
            }

        }

    }

    @RequiresApi(api = Build.VERSION_CODES.HONEYCOMB)
    @Override
    public void onDialogPositiveClick(AddFoodDialogFragment dialog) {
        @SuppressLint("SimpleDateFormat") SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-DD HH:MM:SS.SSS");
        String time = df.format(Calendar.getInstance().getTime());
        // Save image to storage
        if (imageName == null) {
            imageName = time + ".jpg";
            imageName = saveBitmap(croppedBitmap, imageName);
        }
        // Add entry to database
        Entry entry = new Entry(dialog.getCode(), time, dialog.getAmount(), imageName);
        entryRepository.addEntry(entry);
        Toast.makeText(this, "Added to diary", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onDialogNegativeClick(AddFoodDialogFragment dialog) {
        // User touched the dialog's negative button
    }


    /* Respond to the action bar's Up/Home button */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                NavUtils.navigateUpFromSameTask(this);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }


    /* Save bitmapp to internal storage and return image name */
    private String saveBitmap(Bitmap bitmap, String imageName) {
        ContextWrapper cw = new ContextWrapper(this);
        File directory = cw.getDir("imageDir", Context.MODE_PRIVATE);
        File file = new File(directory, imageName);
        if (!file.exists()) {
            try {
                OutputStream outStream = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outStream);
                outStream.flush();
                outStream.close();

            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        Log.d(TAG, "Saved " + imageName);
        return imageName;
    }


    /**********************************************************************
     * Calculate transformation matrix so that an image can be
     * cropped and used by TensorFlow Classifier
     **********************************************************************/
    private Matrix getTransformationMatrix(
            final int srcWidth,
            final int srcHeight,
            final int dstWidth,
            final int dstHeight,
            final int applyRotation,
            final boolean maintainAspectRatio) {
        final Matrix matrix = new Matrix();

        if (applyRotation != 0) {
            // Translate so center of image is at origin.
            matrix.postTranslate(-srcWidth / 2.0f, -srcHeight / 2.0f);

            // Rotate around origin.
            matrix.postRotate(applyRotation);
        }

        // Account for the already applied rotation, if any, and then determine how
        // much scaling is needed for each axis.
        final boolean transpose = (Math.abs(applyRotation) + 90) % 180 == 0;

        final int inWidth = transpose ? srcHeight : srcWidth;
        final int inHeight = transpose ? srcWidth : srcHeight;

        // Apply scaling if necessary.
        if (inWidth != dstWidth || inHeight != dstHeight) {
            final float scaleFactorX = dstWidth / (float) inWidth;
            final float scaleFactorY = dstHeight / (float) inHeight;

            if (maintainAspectRatio) {
                // Scale by minimum factor so that dst is filled completely while
                // maintaining the aspect ratio. Some image may fall off the edge.
                final float scaleFactor = Math.max(scaleFactorX, scaleFactorY);
                matrix.postScale(scaleFactor, scaleFactor);
            } else {
                // Scale exactly to fill dst from src.
                matrix.postScale(scaleFactorX, scaleFactorY);
            }
        }

        if (applyRotation != 0) {
            // Translate back from origin centered reference to destination frame.
            matrix.postTranslate(dstWidth / 2.0f, dstHeight / 2.0f);
        }

        return matrix;
    }



    /*****************************************************************
     * AsyncTask: Classify image in background
     *****************************************************************/
    @SuppressLint("StaticFieldLeak")
    private class ClassifyImageTask extends AsyncTask<Bitmap, Void, List> {
        @Override
        protected List doInBackground(Bitmap... bitmap) {
            // Create image classifier
            Classifier classifier =
                    TensorFlowImageClassifier.create(
                            getAssets(),
                            MODEL_FILE,
                            LABEL_FILE,
                            INPUT_SIZE,
                            IMAGE_MEAN,
                            IMAGE_STD,
                            INPUT_NAME,
                            OUTPUT_NAME);

            // Classify image

            return classifier.recognizeImage(bitmap[0]);
        }

        @Override
        protected void onPostExecute(List results) {
            // Hide loading panel
            View loadingPanel = findViewById(R.id.loading_panel);
            loadingPanel.setVisibility(View.GONE);

            // Display results
            try {
                for (Object res : results) {
                    Classifier.Recognition recognition = (Classifier.Recognition) res;
                    foodInfos.add(getFoodInfoFromName(recognition.getTitle()));
                }
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }

            adapter.notifyDataSetChanged();
        }

        Food getFoodInfoFromName(String foodName) throws JSONException {
            JSONObject foodInfo = jsonReader.getJSONObject(foodName);
            String name = foodInfo.getString("name");
            int serving = foodInfo.getInt("serving");
            int cal = foodInfo.getInt("calories");
            double protein = foodInfo.getDouble("protein");
            double fat = foodInfo.getDouble("fat");
            double carb = foodInfo.getDouble("carb");
            String unit = foodInfo.getString("unit");
            return new Food(foodName, name, unit, serving, cal, protein, fat, carb);
        }
    }

}
