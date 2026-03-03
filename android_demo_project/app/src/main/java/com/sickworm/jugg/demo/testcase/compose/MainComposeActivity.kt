package com.sickworm.jugg.demo.testcase.compose

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.R
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData



class MainComposeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column {
                SongItem()
                Spacer(modifier = Modifier.padding(vertical = 10.dp))
                SongItemPreview()
                Spacer(modifier = Modifier.padding(vertical = 10.dp))
                ContentPreview()
            }
        }
    }

    @Preview
    @Composable
    fun SongItem(
        itemData: SongItemData = SongItemData(
            R.drawable.ic_launcher_background,
            "Never Gonna Give You Up",
            "data",
            0
        )
    ) {
        Row(Modifier.background(Color.LightGray)) {
            Image(painter = painterResource(id = itemData.coverResId), contentDescription = "song cover")
            Column(Modifier.padding(8.dp, 4.dp, 8.dp, 0.dp)) {
                Text(text = itemData.title, fontSize = 14.sp)
                Row {
                    Text(text = itemData.author)
                    Text(text = "playCount:${itemData.playCount}")
                }
            }
        }
    }

    @Preview
    @Composable
    fun SongItemPreview() {
        Row(Modifier.background(Color.LightGray)) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_background),
                modifier = Modifier.size(48.dp), contentDescription = "song cover2"
            )
            Column(Modifier.padding(8.dp, 4.dp, 8.dp, 0.dp)) {
                Text(text = "Never Gonna Give You Up2", fontSize = 14.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = "data2", fontSize = 12.sp)
                    Text(text = "play count:1", Modifier.padding(8.dp, 0.dp, 0.dp, 0.dp), fontSize = 10.sp)
                }
            }
        }
    }

    data class SongItemData(val coverResId: Int, val title: String, val author: String, val playCount: Int)


    @Preview
    @Composable
    fun ContentPreview() {
        Content {
            Log.d("ContentPreview", "click")
        }
    }

    @Composable
    fun Content(onclick: () -> Unit) {

        val countData = object : MutableLiveData<Int>() {
            fun add() {
                postValue(1)
            }
        }

        Row(
            modifier = Modifier
                .background(color = Color(android.graphics.Color.parseColor("#B794f6")))
                .padding(horizontal = 10.dp)
                .fillMaxWidth()
        ) {

            Image(
                painter = painterResource(id = android.R.drawable.star_on),
                contentDescription = "a image",
                Modifier.clickable {
                    onclick.invoke()
                    countData.add()
                })
            Spacer(modifier = Modifier.padding(horizontal = 10.dp))
            Column {
                Log.d("Content", "Column info")
                Row {
                    Text(text = "hello ${countData.value}", color = Color.White)
                    Text(text = " compose", color = Color.Blue)
                }
                Spacer(modifier = Modifier.padding(horizontal = 10.dp))
                Text(text = "Hello world", color = Color.Red, modifier = Modifier.size(100.dp, 50.dp))
            }
        }
    }

}