package com.bloomington.transit.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.bloomington.transit.R
import com.bloomington.transit.data.api.GtfsStaticParser
import com.bloomington.transit.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var gtfsParser: GtfsStaticParser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gtfsParser = GtfsStaticParser(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController

        val topDests = setOf(
            R.id.routeMapFragment,
            R.id.scheduleFragment,
            R.id.tripPlannerFragment,
            R.id.favoritesFragment
        )
        val appBarConfig = AppBarConfiguration(topDests)
        setupActionBarWithNavController(navController, appBarConfig)
        binding.bottomNav.setupWithNavController(navController)

        requestNotificationPermission()
        loadGtfsData()
    }

    private fun loadGtfsData() {
        lifecycleScope.launch {
            val fromCache = gtfsParser.loadFromCache()
            if (!fromCache) {
                Toast.makeText(this@MainActivity, "Downloading transit data…", Toast.LENGTH_SHORT).show()
                val ok = gtfsParser.loadFromNetwork()
                if (!ok) Toast.makeText(this@MainActivity, "Failed to load transit data", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp() || super.onSupportNavigateUp()
}
