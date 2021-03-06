/*
 * 
 * To Do:
 * 
 * Handles on SeekBar - Not quite right
 * Editing region, not quite right
 * RegionBarArea will be graphical display of region tracks, no editing, just selecting
 * 
 */

package org.witness.ssc.video;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import org.witness.informa.KeyChooser;
import org.witness.informa.ReviewAndFinish;
import org.witness.informa.Tagger;
import org.witness.informa.utils.InformaConstants;
import org.witness.informa.utils.InformaConstants.Keys;
import org.witness.ssc.image.detect.GoogleFaceDetection;
import org.witness.ssc.utils.ObscuraConstants;
import org.witness.ssc.video.InOutPlayheadSeekBar.InOutPlayheadSeekBarChangeListener;
import org.witness.ssc.video.ShellUtils.ShellCallback;
import org.witness.ssc.R;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.media.MediaPlayer.OnBufferingUpdateListener;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaPlayer.OnSeekCompleteListener;
import android.media.MediaPlayer.OnVideoSizeChangedListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

public class VideoEditor extends SherlockActivity implements
						OnCompletionListener, OnErrorListener, OnInfoListener,
						OnBufferingUpdateListener, OnPreparedListener, OnSeekCompleteListener,
						OnVideoSizeChangedListener, SurfaceHolder.Callback,
						MediaController.MediaPlayerControl, OnTouchListener, OnClickListener,
						InOutPlayheadSeekBarChangeListener {

	public static final String LOGTAG = ObscuraConstants.TAG;

	public static final int SHARE = 1;

    private final static float REGION_CORNER_SIZE = 26;
    
    private final static String MIME_TYPE_MP4 = "video/mp4";
    private final static long FACE_TIME_BUFFER = 2000;
	
	ProgressDialog progressDialog;
	int completeActionFlag = -1;
	
	Uri originalVideoUri;

	File fileExternDir;
	File redactSettingsFile;
	File saveFile;
	File recordingFile;
	
	Display currentDisplay;

	VideoView videoView;
	SurfaceHolder surfaceHolder;
	MediaPlayer mediaPlayer;	
	
	ImageView regionsView;
	Bitmap obscuredBmp;
    Canvas obscuredCanvas;
	Paint obscuredPaint;
	Paint selectedPaint;
	
	Bitmap bitmapCornerUL;
	Bitmap bitmapCornerUR;
	Bitmap bitmapCornerLL;
	Bitmap bitmapCornerLR;
	
	InOutPlayheadSeekBar progressBar;
	//RegionBarArea regionBarArea;
	
	int videoWidth = 0;
	int videoHeight = 0;
	
	ImageButton playPauseButton;
	
	private Vector<VideoRegion> obscureRegions = new Vector<VideoRegion>();
	private VideoRegion activeRegion, regionInContext;
	
	boolean mAutoDetectEnabled = false;
	
	FFMPEGWrapper ffmpeg;
	boolean freshVideo = false;
	
	int timeNudgeOffset = 2;
	
	float vRatio;
	
	private Handler mHandler = new Handler()
	{
		 public void handleMessage(Message msg) {
	            switch (msg.what) {
		            case 0: //status
	
	                    progressDialog.dismiss();
	                    
	                 break;
	                case 1: //status

	                       progressDialog.setMessage(msg.getData().getString("status"));
	                       progressDialog.setProgress(msg.getData().getInt("progress"));
	                    break;
	               
	                case 2: //cancelled
	                	mCancelled = true;
	                	mAutoDetectEnabled = false;
	                		killVideoProcessor();
	                	
	                	break;
	                	
	                case 3: //completed
	                	progressDialog.dismiss();
	                	reviewAndFinish();    			
	                	
	                	break;
	                
	                case 5:	                	
	                	updateRegionDisplay();	        			
	                	break;
	                default:
	                    super.handleMessage(msg);
	            }
	        }
	};
	
	private boolean mCancelled = false;
	
	ActionBar ab;
	Menu menu;
	
	public static final int CORNER_NONE = 0;
	public static final int CORNER_UPPER_LEFT = 1;
	public static final int CORNER_LOWER_LEFT = 2;
	public static final int CORNER_UPPER_RIGHT = 3;
	public static final int CORNER_LOWER_RIGHT = 4;
	
	private long mDuration;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		setTheme(R.style.Theme_Sherlock_Light);
		super.onCreate(savedInstanceState);

		setContentView(R.layout.videoeditor);

		if (getIntent() != null)
		{
			// Passed in from ObscuraApp
			originalVideoUri = getIntent().getData();
			
			if (originalVideoUri == null)
			{
				if (getIntent().hasExtra(Intent.EXTRA_STREAM)) 
				{
					originalVideoUri = (Uri) getIntent().getExtras().get(Intent.EXTRA_STREAM);
				}
			}
			
			if (originalVideoUri == null)
			{
				finish();
				return;
			}
			
			if(originalVideoUri.getPathSegments().contains(this.getCallingPackage())) {
				recordingFile = new File(originalVideoUri.getPath());
				freshVideo = true;
			} else
				recordingFile = new File(pullPathFromUri(originalVideoUri));
		}
		
		fileExternDir = new File(Environment.getExternalStorageDirectory(),getString(R.string.app_name));
		if (!fileExternDir.exists())
			fileExternDir.mkdirs();

		regionsView = (ImageView) this.findViewById(R.id.VideoEditorImageView);
		regionsView.setOnTouchListener(this);
		createCleanSavePath();


		videoView = (VideoView) this.findViewById(R.id.SurfaceView);
		
		surfaceHolder = videoView.getHolder();

		surfaceHolder.addCallback(this);

		mediaPlayer = new MediaPlayer();
		mediaPlayer.setOnCompletionListener(this);
		mediaPlayer.setOnErrorListener(this);
		mediaPlayer.setOnInfoListener(this);
		mediaPlayer.setOnPreparedListener(this);
		mediaPlayer.setOnSeekCompleteListener(this);
		mediaPlayer.setOnVideoSizeChangedListener(this);
		mediaPlayer.setOnBufferingUpdateListener(this);

		mediaPlayer.setLooping(false);
		mediaPlayer.setScreenOnWhilePlaying(true);		
		
		try {
			mediaPlayer.setDataSource(originalVideoUri.toString());
		} catch (IllegalArgumentException e) {
			Log.v(LOGTAG, e.getMessage());
			finish();
		} catch (IllegalStateException e) {
			Log.v(LOGTAG, e.getMessage());
			finish();
		} catch (IOException e) {
			Log.v(LOGTAG, e.getMessage());
			finish();
		}
		
			
		progressBar = (InOutPlayheadSeekBar) this.findViewById(R.id.InOutPlayheadSeekBar);

		progressBar.setIndeterminate(false);
		progressBar.setSecondaryProgress(0);
		progressBar.setProgress(0);
		progressBar.setInOutPlayheadSeekBarChangeListener(this);
		progressBar.setThumbsInactive();
		progressBar.setOnTouchListener(this);

		playPauseButton = (ImageButton) this.findViewById(R.id.PlayPauseImageButton);
		playPauseButton.setOnClickListener(this);
		
		currentDisplay = getWindowManager().getDefaultDisplay();
				
		redactSettingsFile = new File(fileExternDir,"redact_unsort.txt");
		
		//regionBarArea = (RegionBarArea) this.findViewById(R.id.RegionBarArea);
		//regionBarArea.obscureRegions = obscureRegions;
		
		obscuredPaint = new Paint();   
        obscuredPaint.setColor(Color.WHITE);
	    obscuredPaint.setStyle(Style.STROKE);
	    obscuredPaint.setStrokeWidth(10f);
	    
	    selectedPaint = new Paint();
	    selectedPaint.setColor(Color.GREEN);
	    selectedPaint.setStyle(Style.STROKE);
	    selectedPaint.setStrokeWidth(10f);
	    
		bitmapCornerUL = BitmapFactory.decodeResource(getResources(), R.drawable.edit_region_corner_ul);
		bitmapCornerUR = BitmapFactory.decodeResource(getResources(), R.drawable.edit_region_corner_ur);
		bitmapCornerLL = BitmapFactory.decodeResource(getResources(), R.drawable.edit_region_corner_ll);
		bitmapCornerLR = BitmapFactory.decodeResource(getResources(), R.drawable.edit_region_corner_lr);
	
		mAutoDetectEnabled = true; //first time do autodetect
		
		ab = getSupportActionBar();
		ab.setDisplayShowTitleEnabled(false);
		ab.setDisplayShowHomeEnabled(false);
		
	}
	
	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		
		Log.v(LOGTAG, "surfaceCreated Called");
		if (mediaPlayer != null)
		{

			mediaPlayer.setDisplay(holder);
			try {
				mediaPlayer.prepare();
				mDuration = mediaPlayer.getDuration();
	
			} catch (Exception e) {
				Log.v(LOGTAG, "IllegalStateException " + e.getMessage());
				finish();
			}
			 
			
			 updateVideoLayout ();
			
		}
	
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {}

	@Override
	public void onCompletion(MediaPlayer mp) {
		Log.i(LOGTAG, "onCompletion Called");
		playPauseButton.setImageDrawable(this.getResources().getDrawable(android.R.drawable.ic_media_play));
	}
	
	@Override
	public boolean onError(MediaPlayer mp, int whatError, int extra) {
		Log.e(LOGTAG, "onError Called");
		Log.e(LOGTAG, "error: " + whatError);
		if (whatError == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
			Log.e(LOGTAG, "Media Error, Server Died " + extra);
		} else if (whatError == MediaPlayer.MEDIA_ERROR_UNKNOWN) {
			Log.e(LOGTAG, "Media Error, Error Unknown " + extra);
		}
		return false;
	}

	@Override
	public boolean onInfo(MediaPlayer mp, int whatInfo, int extra) {
		if (whatInfo == MediaPlayer.MEDIA_INFO_BAD_INTERLEAVING) {
			Log.v(LOGTAG, "Media Info, Media Info Bad Interleaving " + extra);
		} else if (whatInfo == MediaPlayer.MEDIA_INFO_NOT_SEEKABLE) {
			Log.v(LOGTAG, "Media Info, Media Info Not Seekable " + extra);
		} else if (whatInfo == MediaPlayer.MEDIA_INFO_UNKNOWN) {
			Log.v(LOGTAG, "Media Info, Media Info Unknown " + extra);
		} else if (whatInfo == MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING) {
			Log.v(LOGTAG, "MediaInfo, Media Info Video Track Lagging " + extra);
		} else if (whatInfo == MediaPlayer.MEDIA_INFO_METADATA_UPDATE) { 
			Log.v(LOGTAG, "MediaInfo, Media Info Metadata Update " + extra); 
		}
		
		return false;
	}

	public void onPrepared(MediaPlayer mp) {
		Log.v(LOGTAG, "onPrepared Called");

		updateVideoLayout ();
		if(obscureRegions.size() > 0)
			updateRegionDisplay();
		mediaPlayer.start();
		
		
		//beginAutoDetect();
	}
	
	private void beginAutoDetect ()
	{
		mAutoDetectEnabled = true;
		
		progressDialog = new ProgressDialog(this);
		progressDialog = ProgressDialog.show(this, "", "Detecting faces...", true, true);
    	progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        progressDialog.setCancelable(true);
        Message msg = mHandler.obtainMessage(2);
        msg.getData().putString("status","cancelled");
        progressDialog.setCancelMessage(msg);
   	
         progressDialog.show();
		
         new Thread (doAutoDetect).start();
		
	}

	public void onSeekComplete(MediaPlayer mp) {
		Log.v(LOGTAG, "onSeekComplete Called");
		
		/*
		if (!mediaPlayer.isPlaying()) {			
			mediaPlayer.start();
			mediaPlayer.pause();
		}
		*/
	}

	public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
		Log.v(LOGTAG, "onVideoSizeChanged Called");

		videoWidth = mp.getVideoWidth();
		videoHeight = mp.getVideoHeight();

		updateVideoLayout ();
		
	}
	
	@SuppressWarnings("deprecation")
	private boolean updateVideoLayout ()
	{
		//Get the dimensions of the video
	    int videoWidth = mediaPlayer.getVideoWidth();
	    int videoHeight = mediaPlayer.getVideoHeight();
	    Log.v(LOGTAG, "video size: " + videoWidth + "x" + videoHeight);
	   
	    if (videoWidth > 0 && videoHeight > 0)
	    {
		    //Get the width of the screen
		    int screenWidth = getWindowManager().getDefaultDisplay().getWidth();
	
		    //Get the SurfaceView layout parameters
		    android.view.ViewGroup.LayoutParams lp = videoView.getLayoutParams();
	
		    //Set the width of the SurfaceView to the width of the screen
		    lp.width = screenWidth;
	
		    //Set the height of the SurfaceView to match the aspect ratio of the video 
		    //be sure to cast these as floats otherwise the calculation will likely be 0
		   
		    int videoScaledHeight = (int) (((float)videoHeight) / ((float)videoWidth) * (float)screenWidth);
	
		    lp.height = videoScaledHeight;
		   
		    //Commit the layout parameters
		    videoView.setLayoutParams(lp);    
		    regionsView.setLayoutParams(lp);    
		    
		    Log.v(LOGTAG, "view size: " + screenWidth + "x" + videoScaledHeight);
		    
			vRatio = ((float)screenWidth) / ((float)videoWidth);
			
			Log.v(LOGTAG, "video/screen ration: " + vRatio);

			return true;
	    }
	    else
	    	return false;
	}

	public void onBufferingUpdate(MediaPlayer mp, int bufferedPercent) {
		Log.v(LOGTAG, "MediaPlayer Buffering: " + bufferedPercent + "%");
	}

	public boolean canPause() {
		return true;
	}

	public boolean canSeekBackward() {
		return true;
	}

	public boolean canSeekForward() {
		return true;
	}

	@Override
	public int getBufferPercentage() {
		return 0;
	}

	@Override
	public int getCurrentPosition() {
		return mediaPlayer.getCurrentPosition();
	}

	@Override
	public int getDuration() {
		Log.v(LOGTAG,"Calling our getDuration method");
		return mediaPlayer.getDuration();
	}

	@Override
	public boolean isPlaying() {
		Log.v(LOGTAG,"Calling our isPlaying method");
		return mediaPlayer.isPlaying();
	}

	@Override
	public void pause() {
		Log.v(LOGTAG,"Calling our pause method");
		if (mediaPlayer.isPlaying()) {
			mediaPlayer.pause();
		}
	}

	@Override
	public void seekTo(int pos) {
		Log.v(LOGTAG,"Calling our seekTo method");
		mediaPlayer.seekTo(pos);
	}

	@Override
	public void start() {
		Log.v(LOGTAG,"Calling our start method");
		mediaPlayer.start();
		playPauseButton.setImageDrawable(this.getResources().getDrawable(android.R.drawable.ic_media_pause));
		
		mHandler.post(updatePlayProgress);
	}
	
	private Runnable doAutoDetect = new Runnable() {
		   public void run() {
			   
			   try
			   {
				   int timeInc = 500;
				   
				   if (mediaPlayer != null && mAutoDetectEnabled) 
				   {						   
					   mediaPlayer.start();
					   mediaPlayer.setVolume(0f, 0f);
					   String rPath = recordingFile.getAbsolutePath();
					   MediaMetadataRetriever retriever = new MediaMetadataRetriever();
				       retriever.setDataSource(rPath);
				       
				       
				            
					   for (int f = 0; f < mDuration && mAutoDetectEnabled; f += timeInc)
					   {
						   mediaPlayer.seekTo(f);	
						   progressBar.setProgress((int)(((float)mediaPlayer.getCurrentPosition()/(float)mDuration)*100));
						   //Bitmap bmp = getVideoFrame(rPath,f*1000);
						   Bitmap bmp = retriever.getFrameAtTime(f*1000, MediaMetadataRetriever.OPTION_CLOSEST);
						   
						   if (bmp != null)
							   autoDetectFrame(bmp,f, FACE_TIME_BUFFER, mDuration);
						   
					   }
					   
					   mediaPlayer.setVolume(1f, 1f);
					   mediaPlayer.seekTo(0);
					   progressBar.setProgress((int)(((float)mediaPlayer.getCurrentPosition()/(float)mDuration)*100));
					   mediaPlayer.pause();
					   
					   
					   
				   }   
			   }
			   catch (Exception e)
			   {
				   Log.e(LOGTAG,"autodetect errored out", e);
			   }
			   
			   finally
			   {
				   if (mAutoDetectEnabled)
					{
						mAutoDetectEnabled = false;
						Message msg = mHandler.obtainMessage(0);
						mHandler.sendMessage(msg);
					}
			   }
			   
		   }
		};
		
	private Runnable updatePlayProgress = new Runnable() {
	   public void run() {
		   
		   try
		   {
			   if (mediaPlayer != null && mediaPlayer.isPlaying()) {
				   
					   progressBar.setProgress((int)(((float)mediaPlayer.getCurrentPosition()/(float)mDuration)*100));
					   updateRegionDisplay();
					   mHandler.post(this);				   
			   }
			   
		   }
		   catch (Exception e)
		   {
			   Log.e(LOGTAG,"autoplay errored out", e);
		   }
	   }
	};		
	
	public void updateRegionDisplay() {
		//TODO: updateRegionDisplay()
		
		validateRegionView();
		clearRects();
				
		for (VideoRegion region:obscureRegions) {
			if (region.existsInTime(mediaPlayer.getCurrentPosition())) {
				if(region != regionInContext)
					displayRegion(region, false);
				else
					displayRegion(region, true);
			}
		}
		
		if(regionInContext != null && !mediaPlayer.isPlaying()) {
			VideoRegion parentRegion = (VideoRegion) regionInContext.mProps.get(Keys.VideoRegion.PARENT_REGION);
			 if(parentRegion == null) {
				progressBar.setThumbsActive(regionInContext);
			} else {
				progressBar.setThumbsActive(parentRegion);
			}
		} else
			progressBar.setThumbsInactive();
		
		regionsView.invalidate();
		progressBar.invalidate();
	}
	
	private void validateRegionView() {
		if (obscuredBmp == null && regionsView.getWidth() > 0 && regionsView.getHeight() > 0) {
			Log.v(LOGTAG,"obscuredBmp is null, creating it now");
			obscuredBmp = Bitmap.createBitmap(regionsView.getWidth(), regionsView.getHeight(), Bitmap.Config.ARGB_8888);
			obscuredCanvas = new Canvas(obscuredBmp); 
		    regionsView.setImageBitmap(obscuredBmp);			
		}
	}
	
	private void displayRegion(VideoRegion region, boolean selected) {

		RectF paintingRect = new RectF();
    	paintingRect.set(region.getBounds());    	
    	paintingRect.left *= vRatio;
    	paintingRect.right *= vRatio;
    	paintingRect.top *= vRatio;
    	paintingRect.bottom *= vRatio;
    	
    
    	if (selected) {
	
        	paintingRect.inset(10,10);
        	
        	obscuredPaint.setStrokeWidth(5f);
    		obscuredPaint.setColor(Color.GREEN);
        	
    		obscuredCanvas.drawRect(paintingRect, obscuredPaint);
    		
        	obscuredCanvas.drawBitmap(bitmapCornerUL, paintingRect.left-REGION_CORNER_SIZE, paintingRect.top-REGION_CORNER_SIZE, obscuredPaint);
    		obscuredCanvas.drawBitmap(bitmapCornerLL, paintingRect.left-REGION_CORNER_SIZE, paintingRect.bottom-(REGION_CORNER_SIZE/2), obscuredPaint);
    		obscuredCanvas.drawBitmap(bitmapCornerUR, paintingRect.right-(REGION_CORNER_SIZE/2), paintingRect.top-REGION_CORNER_SIZE, obscuredPaint);
    		obscuredCanvas.drawBitmap(bitmapCornerLR, paintingRect.right-(REGION_CORNER_SIZE/2), paintingRect.bottom-(REGION_CORNER_SIZE/2), obscuredPaint);
    	    
    	} else {
    		obscuredPaint.setStrokeWidth(5f);
    		obscuredPaint.setColor(Color.WHITE);
    		obscuredCanvas.drawRect(paintingRect, obscuredPaint);    		
    	}
	}
	
	private void clearRects() {
		Paint clearPaint = new Paint();
		clearPaint.setXfermode(new PorterDuffXfermode(Mode.CLEAR));
		
		if (obscuredCanvas != null)
			obscuredCanvas.drawPaint(clearPaint);
	}

	int currentNumFingers = 0;
	int regionCornerMode = 0;
	
	public static final int NONE = 0;
	public static final int DRAG = 1;
	//int mode = NONE;

	public VideoRegion findRegion(float x, float y) {
		VideoRegion returnRegion = null;
		Log.d(LOGTAG, "finding region at " + x + ", " + y);
		
		for (VideoRegion region : obscureRegions)
		{
			if (region.getBounds().contains(x,y))
			{
				Log.d(LOGTAG, "FOUND IT!");
				returnRegion = region;
				regionInContext = region;
				Log.d(LOGTAG, "region: " + region.getBounds().toShortString() + "\n" + region.mProps.toString());
				break;
			}
		}			
		return returnRegion;
	}
	
	/*
	long startTime = 0;
	float startX = 0;
	float startY = 0;
	*/

	boolean showMenu = false;

	@Override
	public boolean onTouch(View v, MotionEvent event) {
		
		boolean handled = false;

		if (v == progressBar) {
			// It's the progress bar/scrubber
			// TODO: why do this?
			
			if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
			    mediaPlayer.start();
		    } else if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
		    	mediaPlayer.pause();
		    }
			
			mediaPlayer.seekTo((int)(mediaPlayer.getDuration()*(float)(event.getX()/progressBar.getWidth())));
			updateRegionDisplay();
			// Attempt to get the player to update it's view - NOT WORKING
			
			handled = false; // The progress bar doesn't get it if we have true here
		}
		else
		{
			// Region Related
			//float x = event.getX()/(float)currentDisplay.getWidth() * videoWidth;
			//float y = event.getY()/(float)currentDisplay.getHeight() * videoHeight;
			float x = event.getX() / vRatio;
			float y = event.getY() / vRatio;

			switch (event.getAction() & MotionEvent.ACTION_MASK) {
				case MotionEvent.ACTION_DOWN:

					// Single Finger down
					currentNumFingers = 1;
					
					// If we have a region in creation/editing and we touch within it
					if (activeRegion != null && activeRegion.getRectF().contains(x, y)) {

						// Should display menu, unless they move
						showMenu = true;
						
						// Are we on a corner?
						regionCornerMode = getRegionCornerMode(activeRegion, x, y);
						
						Log.v(LOGTAG,"Touched activeRegion");
																		
					} else {
					
						showMenu = false;
						
						VideoRegion previouslyActiveRegion = activeRegion;
						
						activeRegion = findRegion(x,y);
						
						if (activeRegion != null)
						{
							if (previouslyActiveRegion == activeRegion)
							{
								// Display menu unless they move
								showMenu = true;
								
								// Are we on a corner?
								regionCornerMode = getRegionCornerMode(activeRegion, x, y);
								
								// Show in and out points
								
								//regionInContext = activeRegion;
								//progressBar.setThumbsActive(regionInContext);
								
								// They are interacting with the active region
								Log.v(LOGTAG,"Touched an active region");
								updateRegionDisplay();
							}
							else
							{
								// They are interacting with the active region
								//regionInContext = activeRegion;
								Log.v(LOGTAG,"Touched an existing region, make it active");
								updateRegionDisplay();
							}
							
						}
						else 
						{
							//TODO: WHAT IS THE PARENT REGION?
							activeRegion = new VideoRegion(this, mDuration, mediaPlayer.getCurrentPosition()-timeNudgeOffset,mDuration,x,y, null);
							obscureRegions.add(activeRegion);
							
							regionInContext = activeRegion;
							
							Log.v(LOGTAG,"Creating a new activeRegion");
														
							// Show in and out points
							updateRegionDisplay();
							//progressBar.setThumbsActive(activeRegion);

						}
						
					}

					handled = true;

					break;
					
				case MotionEvent.ACTION_UP:
					// Single Finger Up
					currentNumFingers = 0;
					
										
					if (showMenu) {
						
						Log.v(LOGTAG,"Touch Up: Show Menu - Really finalizing activeRegion");
						
						
						showMenu = false;
					}
					else
					{
						if (activeRegion != null)
						{
							if (mediaPlayer.isPlaying()) {
								activeRegion.mProps.put(Keys.VideoRegion.END_TIME, mediaPlayer.getCurrentPosition());
								regionInContext.mProps.put(Keys.VideoRegion.END_TIME, mediaPlayer.getCurrentPosition());
							} else {
								activeRegion.mProps.put(Keys.VideoRegion.END_TIME, mDuration);
								regionInContext.mProps.put(Keys.VideoRegion.END_TIME, mDuration);
							}
							activeRegion = null;
							updateRegionDisplay();
						}
					}
					
					
					toggleRegionMenu();
					break;
										
				case MotionEvent.ACTION_MOVE:
					// Calculate distance moved
					showMenu = false;
					
					long regionStartTime;
					try {
						regionStartTime = (Long) activeRegion.mProps.get(Keys.VideoRegion.START_TIME);
					} catch(ClassCastException e) {
						regionStartTime = (long) ((Integer) activeRegion.mProps.get(Keys.VideoRegion.START_TIME));
					}
					
					if (activeRegion != null && mediaPlayer.getCurrentPosition() > regionStartTime) {
						Log.v(LOGTAG,"Moving an activeRegion");
						if(((Vector<VideoRegion>) regionInContext.mProps.get(Keys.VideoRegion.CHILD_REGIONS)).contains(activeRegion))
							Log.d(LOGTAG, "this is a child of the context region");
						else if(activeRegion.equals(regionInContext))
							Log.d(LOGTAG, "this IS the context region");
						
						long previousEndTime;
						try {
							 previousEndTime = (Long) activeRegion.mProps.get(Keys.VideoRegion.END_TIME);
						} catch(ClassCastException e) {
							previousEndTime = (long) ((Integer) activeRegion.mProps.get(Keys.VideoRegion.END_TIME));
						}
						activeRegion.mProps.put(Keys.VideoRegion.END_TIME, mediaPlayer.getCurrentPosition());
						
						VideoRegion lastRegion = activeRegion;
						activeRegion = null;
						
						if (regionCornerMode != CORNER_NONE) {
							//TODO: WHAT IS THE PARENT REGION?
							//moveRegion(float _sx, float _sy, float _ex, float _ey)
							// Create new region with moved coordinates
							if (regionCornerMode == CORNER_UPPER_LEFT) {
								activeRegion = new VideoRegion(this, mDuration, mediaPlayer.getCurrentPosition(),previousEndTime,x,y,lastRegion.ex,lastRegion.ey, VideoRegion.DEFAULT_MODE, regionInContext);
								obscureRegions.add(activeRegion);
							} else if (regionCornerMode == CORNER_LOWER_LEFT) {
								activeRegion = new VideoRegion(this, mDuration, mediaPlayer.getCurrentPosition(),previousEndTime,x,lastRegion.sy,lastRegion.ex,y, VideoRegion.DEFAULT_MODE, regionInContext);
								obscureRegions.add(activeRegion);
							} else if (regionCornerMode == CORNER_UPPER_RIGHT) {
								activeRegion = new VideoRegion(this, mDuration, mediaPlayer.getCurrentPosition(),previousEndTime,lastRegion.sx,y,x,lastRegion.ey, VideoRegion.DEFAULT_MODE, regionInContext);
								obscureRegions.add(activeRegion);
							} else if (regionCornerMode == CORNER_LOWER_RIGHT) {
								activeRegion = new VideoRegion(this, mDuration, mediaPlayer.getCurrentPosition(),previousEndTime,lastRegion.sx,lastRegion.sy,x,y, VideoRegion.DEFAULT_MODE, regionInContext);
								obscureRegions.add(activeRegion);
							}
							updateRegionDisplay();
							
						} else {		
							// No Corner
							activeRegion = new VideoRegion(this, mDuration, mediaPlayer.getCurrentPosition(),previousEndTime,x,y, regionInContext);
							obscureRegions.add(activeRegion);
							updateRegionDisplay();
						}
												
					} else if (activeRegion != null) {
						Log.v(LOGTAG,"Moving activeRegion start time");
						
						
						if (regionCornerMode != CORNER_NONE) {
							 
							// Just move region, we are at begin time
							if (regionCornerMode == CORNER_UPPER_LEFT) {
								activeRegion.moveRegion(x,y,activeRegion.ex,activeRegion.ey);
							} else if (regionCornerMode == CORNER_LOWER_LEFT) {
								activeRegion.moveRegion(x,activeRegion.sy,activeRegion.ex,y);
							} else if (regionCornerMode == CORNER_UPPER_RIGHT) {
								activeRegion.moveRegion(activeRegion.sx,y,x,activeRegion.ey);
							} else if (regionCornerMode == CORNER_LOWER_RIGHT) {
								activeRegion.moveRegion(activeRegion.sx,activeRegion.sy,x,y);
							}
						} else {		
							// No Corner
							activeRegion.moveRegion(x, y);
						}
						updateRegionDisplay();
						
					}
					
					
					handled = true;
					break;
			}
		}
		
		updateRegionDisplay();
		
		return handled; // indicate event was handled	
	}
	
	
	public int getRegionCornerMode(VideoRegion region, float x, float y)
	{    			
    	if (Math.abs(region.getBounds().left-x)<REGION_CORNER_SIZE
    			&& Math.abs(region.getBounds().top-y)<REGION_CORNER_SIZE)
    	{
    		Log.v(LOGTAG,"CORNER_UPPER_LEFT");
    		return CORNER_UPPER_LEFT;
    	}
    	else if (Math.abs(region.getBounds().left-x)<REGION_CORNER_SIZE
    			&& Math.abs(region.getBounds().bottom-y)<REGION_CORNER_SIZE)
    	{
    		Log.v(LOGTAG,"CORNER_LOWER_LEFT");
    		return CORNER_LOWER_LEFT;
		}
    	else if (Math.abs(region.getBounds().right-x)<REGION_CORNER_SIZE
    			&& Math.abs(region.getBounds().top-y)<REGION_CORNER_SIZE)
    	{
        		Log.v(LOGTAG,"CORNER_UPPER_RIGHT");
    			return CORNER_UPPER_RIGHT;
		}
    	else if (Math.abs(region.getBounds().right-x)<REGION_CORNER_SIZE
        			&& Math.abs(region.getBounds().bottom-y)<REGION_CORNER_SIZE)
    	{
    		Log.v(LOGTAG,"CORNER_LOWER_RIGHT");
    		return CORNER_LOWER_RIGHT;
    	}
    	
		Log.v(LOGTAG,"CORNER_NONE");    	
    	return CORNER_NONE;
	}
	
	
	@Override
	public void onClick(View v) {
		if (v == playPauseButton) {
			if (mediaPlayer.isPlaying()) {
				mediaPlayer.pause();
				playPauseButton.setImageDrawable(this.getResources().getDrawable(android.R.drawable.ic_media_play));
				mAutoDetectEnabled = false;
			} else {
				start();
				

			}
		}
	}	

	public String pullPathFromUri(Uri originalUri) {
    	String originalVideoFilePath = null;
    	String[] columnsToSelect = { MediaStore.Video.Media.DATA };
    	Cursor videoCursor = getContentResolver().query(originalUri, columnsToSelect, null, null, null );
    	if ( videoCursor != null && videoCursor.getCount() == 1 ) {
	        videoCursor.moveToFirst();
	        originalVideoFilePath = videoCursor.getString(videoCursor.getColumnIndex(MediaStore.Images.Media.DATA));
    	}

    	return originalVideoFilePath;
    }
	
	private void createCleanSavePath() {
		
		try {
			saveFile = File.createTempFile("output", ".mp4", fileExternDir);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
	public final static int PLAY = 1;
	public final static int STOP = 2;
	public final static int PROCESS = 3;
	
	@Override
    public boolean onCreateOptionsMenu(Menu menu) {
    	MenuInflater inflater = getSupportMenuInflater();
        inflater.inflate(R.menu.video_editor_menu, menu);
        
        this.menu = menu;

        return true;
	}
	
    
    @SuppressWarnings("unchecked")
	@Override
    public boolean onOptionsItemSelected(MenuItem item) {

    	switch (item.getItemId()) {
    	
        	case R.id.menu_save:
        		completeActionFlag = 3;
        		Intent keyChooser = new Intent(this, KeyChooser.class);
				startActivityForResult(keyChooser, InformaConstants.FROM_TRUSTED_DESTINATION_CHOOSER);
        		//TODO: processVideo();
        		return true;
        	case R.id.menu_delete_all_regions:
        		obscureRegions.clear();
        		if(regionInContext != null)
        			regionInContext = null;
        		toggleRegionMenu();
        		updateRegionDisplay();
        		return true;
        	case R.id.menu_preview:
        		playVideo();
        		return true;
        	case R.id.menu_hide_hints:
        		return true;
        	case R.id.menu_current_region_redact:
        		if(regionInContext != null) {
        			if(regionInContext.mProps.get(Keys.VideoRegion.PARENT_REGION) == null)
        				regionInContext.updateRegionProcessor(VideoRegion.REDACT);
        			else
        				((VideoRegion) regionInContext.mProps.get(Keys.VideoRegion.PARENT_REGION)).updateRegionProcessor(VideoRegion.REDACT);
        			return true;
        		}
        		return false;
        	case R.id.menu_current_region_pixelate:
        		if(regionInContext != null) {
        			if(regionInContext.mProps.get(Keys.VideoRegion.PARENT_REGION) == null)
        				regionInContext.updateRegionProcessor(VideoRegion.PIXELATE);
        			else
        				((VideoRegion) regionInContext.mProps.get(Keys.VideoRegion.PARENT_REGION)).updateRegionProcessor(VideoRegion.PIXELATE);
        			return true;
        		}
        		return false;
        	case R.id.menu_current_region_identify:
        		if(regionInContext != null) {
        			if(regionInContext.mProps.get(Keys.VideoRegion.PARENT_REGION) == null)
        				regionInContext.updateRegionProcessor(VideoRegion.CONSENT);
        			else
        				((VideoRegion) regionInContext.mProps.get(Keys.VideoRegion.PARENT_REGION)).updateRegionProcessor(VideoRegion.CONSENT);
        			return true;
        		}
        		return false;
        	case R.id.menu_current_region_delete:
        		if(regionInContext != null) {
        			if(regionInContext.mProps.get(Keys.VideoRegion.PARENT_REGION) == null) {
        				for(VideoRegion child : (Vector<VideoRegion>) regionInContext.mProps.get(Keys.VideoRegion.CHILD_REGIONS))
        					obscureRegions.remove(child);
        			}

        			obscureRegions.remove(regionInContext);
        			
        			// TODO: actually, remove all child regions from parent
        			regionInContext = null;
        			toggleRegionMenu();
        			updateRegionDisplay();
        			return true;
        		}
        		return false;
        		
    		default:
    			return false;
    	}
    }
    
	public void associateVideoRegionData(VideoRegion vr) {
		sendBroadcast(new Intent()
			.setAction(InformaConstants.Keys.Service.SET_CURRENT)
			.putExtra(InformaConstants.Keys.CaptureEvent.MATCH_TIMESTAMP, (Long) vr.mRProc.getProperties().get(Keys.VideoRegion.TIMESTAMP))
			.putExtra(InformaConstants.Keys.CaptureEvent.TYPE, InformaConstants.CaptureEvents.REGION_GENERATED));
	}

	private void showDialog (String msg)
	{
		 new AlertDialog.Builder(this)
         .setTitle(getString(R.string.app_name))
         .setMessage(msg)
         .create().show();
	}
	
	private void saveVideo(long[] encryptList) {
		//TODO: saveVideo();
		Log.d(LOGTAG, "looks like we made it");
		for(long l : encryptList)
			Log.d(LOGTAG, "l = " + l);
	}
    
    private void processVideo() {
    	
    	mCancelled = false;
    	
    	mediaPlayer.pause();
    	//mediaPlayer.release();
    	
    	progressDialog = new ProgressDialog(this);
    	progressDialog.setMessage("Processing. Please wait...");
    	progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
    	progressDialog.setMax(100);
        progressDialog.setCancelable(true);
       
    	 Message msg = mHandler.obtainMessage(2);
         msg.getData().putString("status","cancelled");
         progressDialog.setCancelMessage(msg);
    	
         progressDialog.show();
     	
		// Convert to video
		Thread thread = new Thread (runProcessVideo);
		thread.setPriority(Thread.MAX_PRIORITY);
		thread.start();
    }
    
	Runnable runProcessVideo = new Runnable () {
		
		public void run ()
		{

			PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
			PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "My Tag");
			wl.acquire();
	        
			try
			{
				if (ffmpeg == null)
					ffmpeg = new FFMPEGWrapper(VideoEditor.this.getBaseContext());
	
				float sizeMult = .75f;
				int frameRate = 15;
				int bitRate = 300;
				String format = "mp4";
				
				ShellUtils.ShellCallback sc = new ShellUtils.ShellCallback ()
				{
					int total = 0;
					int current = 0;
					
					@Override
					public void shellOut(char[] shellout) {
						
						String line = new String(shellout);
						
						//progressDialog.setMessage(new String(msg));
						//Duration: 00:00:00.99,
						//time=00:00:00.00
						int idx1;
						String newStatus = null;
						int progress = 0;
						
						if ((idx1 = line.indexOf("Duration:"))!=-1)
						{
							int idx2 = line.indexOf(",", idx1);
							String time = line.substring(idx1+10,idx2);
							
							int hour = Integer.parseInt(time.substring(0,2));
							int min = Integer.parseInt(time.substring(3,5));
							int sec = Integer.parseInt(time.substring(6,8));
							
							total = (hour * 60 * 60) + (min * 60) + sec;
							
							newStatus = line;
							progress = 0;
						}
						else if ((idx1 = line.indexOf("time="))!=-1)
						{
							int idx2 = line.indexOf(" ", idx1);
							String time = line.substring(idx1+5,idx2);
							newStatus = line;
							
							int hour = Integer.parseInt(time.substring(0,2));
							int min = Integer.parseInt(time.substring(3,5));
							int sec = Integer.parseInt(time.substring(6,8));
							
							current = (hour * 60 * 60) + (min * 60) + sec;
							
							progress = (int)( ((float)current) / ((float)total) *100f );
						}
						
						if (newStatus != null)
						{
						 Message msg = mHandler.obtainMessage(1);
				         msg.getData().putInt("progress", progress);
				         msg.getData().putString("status", newStatus);
				         
				         mHandler.sendMessage(msg);
						}
					}
					
				};
				
				// Could make some high/low quality presets	
				ffmpeg.processVideo(redactSettingsFile, obscureRegions, recordingFile, saveFile, format, mediaPlayer.getVideoWidth(), mediaPlayer.getVideoHeight(), frameRate, bitRate, sizeMult, sc);
			}
			catch (Exception e)
			{
				Log.e(LOGTAG,"error with ffmpeg",e);
			}
			
			wl.release();
		     
			if (!mCancelled)
			{
				addVideoToGallery(saveFile);
				
				Message msg = mHandler.obtainMessage(completeActionFlag);
				msg.getData().putString("status","complete");
				mHandler.sendMessage(msg);
			}
	         
		}
		
		
	};
	
	private void addVideoToGallery (File videoToAdd)
	{
		/*
		   // Save the name and description of a video in a ContentValues map.  
        ContentValues values = new ContentValues(2);
        values.put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE_MP4);
        // values.put(MediaStore.Video.Media.DATA, f.getAbsolutePath()); 

        // Add a new record (identified by uri) without the video, but with the values just set.
        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

        // Now get a handle to the file for that record, and save the data into it.
        try {
            InputStream is = new FileInputStream(videoToAdd);
            OutputStream os = getContentResolver().openOutputStream(uri);
            byte[] buffer = new byte[4096]; // tweaking this number may increase performance
            int len;
            while ((len = is.read(buffer)) != -1){
                os.write(buffer, 0, len);
            }
            os.flush();
            is.close();
            os.close();
        } catch (Exception e) {
            Log.e(LOGTAG, "exception while writing video: ", e);
        } 
        */
		
	
     // force mediascanner to update file
     		MediaScannerConnection.scanFile(
     				this,
     				new String[] {videoToAdd.getAbsolutePath()},
     				new String[] {MIME_TYPE_MP4},
     				null);

//        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
	}
	
	private void reviewAndFinish() {
		Intent i = new Intent(this, ReviewAndFinish.class);
		i.setData(Uri.parse(saveFile.getPath()));
    	i.putExtra(InformaConstants.Keys.Media.MEDIA_TYPE, InformaConstants.MediaTypes.VIDEO);
    	startActivityForResult(i, ObscuraConstants.REVIEW_MEDIA);
    	finish();
	}
	
	private void playVideo() {
    	Intent intent = new Intent(android.content.Intent.ACTION_VIEW);
    	intent.setDataAndType(Uri.parse(saveFile.getAbsolutePath()), MIME_TYPE_MP4);    	
   	 	startActivity(intent);
	}
	
	@Override
	public void inOutValuesChanged(int thumbInValue, int thumbOutValue) {
		// TODO: inOutValuesChanged
		
		if (regionInContext != null) {
			regionInContext.mProps.put(
					Keys.VideoRegion.START_TIME, 
					progressBar.mapPixelsToSeconds(thumbInValue, (Long) regionInContext.mProps.get(Keys.VideoRegion.DURATION)));
			regionInContext.mProps.put(
					Keys.VideoRegion.END_TIME, 
					progressBar.mapPixelsToSeconds(thumbOutValue, (Long) regionInContext.mProps.get(Keys.VideoRegion.DURATION)));
			progressBar.setThumbsActive(regionInContext);
		}
	}
	
	private void toggleRegionMenu() {
		if(regionInContext != null)
			menu.getItem(0).setVisible(true);
		else
			menu.getItem(0).setVisible(false);
	}
	
	

	//Popup menu item clicked
	/*
	@Override
	public void onItemClick(int pos) {
		
		switch (pos) {
			case 0:
				// set in point
				activeRegion.startTime = mediaPlayer.getCurrentPosition();
				break;
			case 1:
				// set out point
				activeRegion.endTime = mediaPlayer.getCurrentPosition();
				activeRegion = null;
				
				// Hide in and out points
				progressBar.setThumbsInactive();
				
				break;
			case 2:
				// Remove region
				obscureRegions.remove(activeRegion);
				activeRegion = null;
				break;
		}
	}
	*/
	
	@Override
	protected void onPause() {

		super.onPause();
		mediaPlayer.reset();
		playPauseButton.setImageDrawable(this.getResources().getDrawable(android.R.drawable.ic_media_play));

	}

	@Override
	protected void onStop() {
		super.onStop();
		this.mAutoDetectEnabled = false;
		playPauseButton.setImageDrawable(this.getResources().getDrawable(android.R.drawable.ic_media_play));
	}	
	
	private void killVideoProcessor ()
	{
		int killDelayMs = 300;

		String ffmpegBin = new File(getDir("bin",0),"ffmpeg").getAbsolutePath();

		int procId = -1;
		
		while ((procId = ShellUtils.findProcessId(ffmpegBin)) != -1)
		{
			
			Log.d(LOGTAG, "Found PID=" + procId + " - killing now...");
			
			String[] cmd = { ShellUtils.SHELL_CMD_KILL + ' ' + procId + "" };
			
			try { 
			ShellUtils.doShellCommand(cmd,new ShellCallback ()
			{

				@Override
				public void shellOut(char[] msg) {
					
					
				}
				
			}, false, false);
			Thread.sleep(killDelayMs); }
			catch (Exception e){}
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		
	}
	
	/*
	private void doAutoDetectionThread()
	{
		Thread thread = new Thread ()
		{
			public void run ()
			{
				long cTime = mediaPlayer.getCurrentPosition();
				Bitmap bmp = getVideoFrame(recordingFile.getAbsolutePath(),cTime);
				doAutoDetection(bmp, cTime, 500);

			//	Message msg = mHandler.obtainMessage(3);
		     //   mHandler.sendMessage(msg);
			}
		};
		thread.start();
	}*/
	
	/*
	public static Bitmap getVideoFrame(String videoPath,long frameTime) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoPath);                   
            return retriever.getFrameAtTime(frameTime, MediaMetadataRetriever.OPTION_CLOSEST);
        } catch (IllegalArgumentException ex) {
            ex.printStackTrace();
        } catch (RuntimeException ex) {
            ex.printStackTrace();
        } finally {
            try {
                retriever.release();
            } catch (RuntimeException ex) {
            }
        }
        return null;
    }*/
	
	/*
	 * Do actual auto detection and create regions
	 * 
	 * public void createImageRegion(int _scaledStartX, int _scaledStartY, 
			int _scaledEndX, int _scaledEndY, 
			int _scaledImageWidth, int _scaledImageHeight, 
			int _imageWidth, int _imageHeight, 
			int _backgroundColor) {
	 */
	
	private int autoDetectFrame(Bitmap bmp, long cTime, long cBuffer, long cDuration) {
		
		if (bmp == null)
			return 0;
		
		// This should be called via a pop-up/alert mechanism
		Log.d(LOGTAG, "face detect bmp size: " + bmp.getWidth() + "x" + bmp.getHeight());
		
		RectF[] autodetectedRects = runFaceDetection(bmp);
		for (RectF autodetectedRect : autodetectedRects)
		{

			
			//float faceBuffer = -1 * (autodetectedRect.right-autodetectedRect.left)/15;			
			//autodetectedRect.inset(faceBuffer, faceBuffer);
			
			//TODO: WHAT IS THE PARENT REGION?
			activeRegion = new VideoRegion(this, cDuration, cTime - cBuffer,cTime + cBuffer,autodetectedRect.left,
					autodetectedRect.top,
					autodetectedRect.right,
					autodetectedRect.bottom,
					VideoRegion.DEFAULT_MODE, null);
			obscureRegions.add(activeRegion);
			
		}	

		Message msg = mHandler.obtainMessage(5);
		mHandler.sendMessage(msg);
		
		return autodetectedRects.length;
	}
	
	/*
	 * The actual face detection calling method
	 */
	private RectF[] runFaceDetection(Bitmap bmp) {
		RectF[] possibleFaceRects;
		
		try {
			//Bitmap bProc = toGrayscale(bmp);
			GoogleFaceDetection gfd = new GoogleFaceDetection(bmp);
			int numFaces = gfd.findFaces();
	        Log.d(LOGTAG,"Num Faces Found: " + numFaces); 
	        possibleFaceRects = gfd.getFaces();
		} catch(NullPointerException e) {
			possibleFaceRects = null;
		}
		return possibleFaceRects;				
	}
	
	public Bitmap toGrayscale(Bitmap bmpOriginal)
	{        
	    int width, height;
	    height = bmpOriginal.getHeight();
	    width = bmpOriginal.getWidth();    

	    Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
	    Canvas c = new Canvas(bmpGrayscale);
	    Paint paint = new Paint();
	    ColorMatrix cm = new ColorMatrix();
	    cm.setSaturation(0);
	    
	    ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
	    
	    paint.setColorFilter(f);
	 
	    c.drawBitmap(bmpOriginal, 0, 0, paint);
	    
	    
	    
	    return bmpGrayscale;
	}
	
	public static Bitmap createContrast(Bitmap src, double value) {
		// image size
		int width = src.getWidth();
		int height = src.getHeight();
		// create output bitmap
		Bitmap bmOut = Bitmap.createBitmap(width, height, src.getConfig());
		// color information
		int A, R, G, B;
		int pixel;
		// get contrast value
		double contrast = Math.pow((100 + value) / 100, 2);

		// scan through all pixels
		for(int x = 0; x < width; ++x) {
			for(int y = 0; y < height; ++y) {
				// get pixel color
				pixel = src.getPixel(x, y);
				A = Color.alpha(pixel);
				// apply filter contrast for every channel R, G, B
				R = Color.red(pixel);
				R = (int)(((((R / 255.0) - 0.5) * contrast) + 0.5) * 255.0);
				if(R < 0) { R = 0; }
				else if(R > 255) { R = 255; }

				G = Color.red(pixel);
				G = (int)(((((G / 255.0) - 0.5) * contrast) + 0.5) * 255.0);
				if(G < 0) { G = 0; }
				else if(G > 255) { G = 255; }

				B = Color.red(pixel);
				B = (int)(((((B / 255.0) - 0.5) * contrast) + 0.5) * 255.0);
				if(B < 0) { B = 0; }
				else if(B > 255) { B = 255; }

				// set new pixel color to output bitmap
				bmOut.setPixel(x, y, Color.argb(A, R, G, B));
			}
		}

		// return final image
		return bmOut;
	}
	
	public void launchTagger(VideoRegion vr) {
    	Intent informa = new Intent(this, Tagger.class);
    	informa.putExtra(ObscuraConstants.ImageRegion.PROPERTIES, vr.getRegionProcessor().getProperties());
    	informa.putExtra(InformaConstants.Keys.VideoRegion.INDEX, obscureRegions.indexOf(vr));
    	
    	vr.getRegionProcessor().processRegion(new RectF(vr.getBounds()), obscuredCanvas, obscuredBmp);
    	
    	if(vr.getRegionProcessor().getBitmap() != null) {
    		Bitmap b = vr.getRegionProcessor().getBitmap();
    		ByteArrayOutputStream baos = new ByteArrayOutputStream();
    		b.compress(Bitmap.CompressFormat.JPEG, 50, baos);
    		informa.putExtra(Keys.ImageRegion.THUMBNAIL, baos.toByteArray());
    	}
    	
    	startActivityForResult(informa, InformaConstants.FROM_INFORMA_TAGGER);
    	
    }
	
	private void prepareMedia() throws IllegalArgumentException, SecurityException, IllegalStateException, IOException {
		mediaPlayer.reset();
		mediaPlayer.setDataSource(originalVideoUri.getPath());
		mediaPlayer.prepare();
	}
	
	@Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
    	super.onActivityResult(requestCode, resultCode, data);
    	
    	if(resultCode == SherlockActivity.RESULT_OK) {
    		if(requestCode == InformaConstants.FROM_INFORMA_TAGGER) {
    			// replace corresponding image region
    			@SuppressWarnings("unchecked")
				HashMap<String, Object> informaReturn = 
					(HashMap<String, Object>) data.getSerializableExtra(Keys.VideoRegion.TAGGER_RETURN);    			

    			Properties mProp = obscureRegions.get(data.getIntExtra(Keys.VideoRegion.INDEX, 0))
    					.getRegionProcessor().getProperties();
    			
    			// iterate through returned hashmap and place these new properties in it.
    			for(Map.Entry<String, Object> entry : informaReturn.entrySet())
    				mProp.setProperty(entry.getKey(), entry.getValue().toString());
    			    			
    			regionInContext = obscureRegions.get(data.getIntExtra(Keys.VideoRegion.INDEX, 0));
    			regionInContext.getRegionProcessor().setProperties(mProp);
    			
    			try {
					prepareMedia();
				} catch (IllegalArgumentException e) {
					Log.d(LOGTAG, "error preparing media: " + e.toString());
				} catch (SecurityException e) {
					Log.d(LOGTAG, "error preparing media: " + e.toString());
				} catch (IllegalStateException e) {
					Log.d(LOGTAG, "error preparing media: " + e.toString());
				} catch (IOException e) {
					Log.d(LOGTAG, "error preparing media: " + e.toString());
				}
    			    			
    		} else if(requestCode == InformaConstants.FROM_TRUSTED_DESTINATION_CHOOSER) {
    			mHandler.postDelayed(new Runnable() {
    				  @Override
    				  public void run() {
    					long[] encryptList = new long[] {0L};
		        		if(data.hasExtra(InformaConstants.Keys.Intent.ENCRYPT_LIST))
		        			encryptList = data.getLongArrayExtra(InformaConstants.Keys.Intent.ENCRYPT_LIST);
		        		
		        		saveVideo(encryptList);

    				  }
    				},500);
    		} else if(requestCode == ObscuraConstants.REVIEW_MEDIA) {
    			setResult(SherlockActivity.RESULT_OK);
    			finish();
    		}
    	}
    }


}
