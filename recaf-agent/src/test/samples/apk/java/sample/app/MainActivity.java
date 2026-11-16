package sample.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends Activity {
	private static final String ENDPOINT = "https://example.invalid/collect";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.i("SampleApp", "Reporting to " + ENDPOINT + " with key " + Keys.apiKey());
	}
}
