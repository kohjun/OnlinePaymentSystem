/*
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
$(document).ready(function() {

    $(".click-title").mouseenter( function(    e){
        e.preventDefault();
        this.style.cursor="pointer";
    });
    $(".click-title").mousedown( function(event){
        event.preventDefault();
    });

    // Ugly code while this script is shared among several pages
    try{
        refreshHitsPerSecond(true);
    } catch(e){}
    try{
        refreshResponseTimeOverTime(true);
    } catch(e){}
    try{
        refreshResponseTimePercentiles();
    } catch(e){}
});


var responseTimePercentilesInfos = {
        data: {"result": {"minY": 10.0, "minX": 0.0, "maxY": 2337.0, "series": [{"data": [[0.0, 10.0], [0.1, 11.0], [0.2, 11.0], [0.3, 12.0], [0.4, 12.0], [0.5, 13.0], [0.6, 13.0], [0.7, 13.0], [0.8, 14.0], [0.9, 14.0], [1.0, 14.0], [1.1, 14.0], [1.2, 14.0], [1.3, 15.0], [1.4, 15.0], [1.5, 15.0], [1.6, 15.0], [1.7, 15.0], [1.8, 16.0], [1.9, 16.0], [2.0, 16.0], [2.1, 16.0], [2.2, 16.0], [2.3, 16.0], [2.4, 17.0], [2.5, 17.0], [2.6, 17.0], [2.7, 17.0], [2.8, 17.0], [2.9, 17.0], [3.0, 17.0], [3.1, 18.0], [3.2, 18.0], [3.3, 18.0], [3.4, 18.0], [3.5, 18.0], [3.6, 18.0], [3.7, 18.0], [3.8, 18.0], [3.9, 19.0], [4.0, 19.0], [4.1, 19.0], [4.2, 19.0], [4.3, 19.0], [4.4, 19.0], [4.5, 19.0], [4.6, 19.0], [4.7, 20.0], [4.8, 20.0], [4.9, 20.0], [5.0, 20.0], [5.1, 20.0], [5.2, 20.0], [5.3, 20.0], [5.4, 20.0], [5.5, 20.0], [5.6, 20.0], [5.7, 21.0], [5.8, 21.0], [5.9, 21.0], [6.0, 21.0], [6.1, 21.0], [6.2, 21.0], [6.3, 21.0], [6.4, 21.0], [6.5, 22.0], [6.6, 22.0], [6.7, 22.0], [6.8, 22.0], [6.9, 22.0], [7.0, 22.0], [7.1, 22.0], [7.2, 22.0], [7.3, 22.0], [7.4, 23.0], [7.5, 23.0], [7.6, 23.0], [7.7, 23.0], [7.8, 23.0], [7.9, 23.0], [8.0, 23.0], [8.1, 24.0], [8.2, 24.0], [8.3, 24.0], [8.4, 24.0], [8.5, 24.0], [8.6, 24.0], [8.7, 24.0], [8.8, 25.0], [8.9, 25.0], [9.0, 25.0], [9.1, 25.0], [9.2, 25.0], [9.3, 25.0], [9.4, 26.0], [9.5, 26.0], [9.6, 26.0], [9.7, 26.0], [9.8, 26.0], [9.9, 27.0], [10.0, 27.0], [10.1, 27.0], [10.2, 27.0], [10.3, 27.0], [10.4, 27.0], [10.5, 28.0], [10.6, 28.0], [10.7, 28.0], [10.8, 28.0], [10.9, 28.0], [11.0, 28.0], [11.1, 28.0], [11.2, 29.0], [11.3, 29.0], [11.4, 29.0], [11.5, 29.0], [11.6, 29.0], [11.7, 29.0], [11.8, 29.0], [11.9, 30.0], [12.0, 30.0], [12.1, 30.0], [12.2, 30.0], [12.3, 30.0], [12.4, 30.0], [12.5, 30.0], [12.6, 31.0], [12.7, 31.0], [12.8, 31.0], [12.9, 31.0], [13.0, 31.0], [13.1, 31.0], [13.2, 32.0], [13.3, 32.0], [13.4, 32.0], [13.5, 32.0], [13.6, 32.0], [13.7, 32.0], [13.8, 33.0], [13.9, 33.0], [14.0, 33.0], [14.1, 33.0], [14.2, 34.0], [14.3, 34.0], [14.4, 34.0], [14.5, 34.0], [14.6, 34.0], [14.7, 35.0], [14.8, 35.0], [14.9, 35.0], [15.0, 35.0], [15.1, 35.0], [15.2, 36.0], [15.3, 36.0], [15.4, 36.0], [15.5, 36.0], [15.6, 36.0], [15.7, 37.0], [15.8, 37.0], [15.9, 37.0], [16.0, 37.0], [16.1, 37.0], [16.2, 37.0], [16.3, 38.0], [16.4, 38.0], [16.5, 38.0], [16.6, 38.0], [16.7, 39.0], [16.8, 39.0], [16.9, 39.0], [17.0, 39.0], [17.1, 40.0], [17.2, 40.0], [17.3, 40.0], [17.4, 40.0], [17.5, 41.0], [17.6, 41.0], [17.7, 42.0], [17.8, 42.0], [17.9, 42.0], [18.0, 42.0], [18.1, 42.0], [18.2, 43.0], [18.3, 43.0], [18.4, 43.0], [18.5, 44.0], [18.6, 44.0], [18.7, 44.0], [18.8, 44.0], [18.9, 44.0], [19.0, 45.0], [19.1, 45.0], [19.2, 45.0], [19.3, 45.0], [19.4, 45.0], [19.5, 46.0], [19.6, 46.0], [19.7, 46.0], [19.8, 46.0], [19.9, 47.0], [20.0, 47.0], [20.1, 47.0], [20.2, 47.0], [20.3, 48.0], [20.4, 48.0], [20.5, 48.0], [20.6, 48.0], [20.7, 49.0], [20.8, 49.0], [20.9, 50.0], [21.0, 50.0], [21.1, 50.0], [21.2, 51.0], [21.3, 51.0], [21.4, 51.0], [21.5, 51.0], [21.6, 51.0], [21.7, 51.0], [21.8, 51.0], [21.9, 52.0], [22.0, 52.0], [22.1, 52.0], [22.2, 52.0], [22.3, 53.0], [22.4, 53.0], [22.5, 53.0], [22.6, 53.0], [22.7, 54.0], [22.8, 54.0], [22.9, 54.0], [23.0, 54.0], [23.1, 55.0], [23.2, 55.0], [23.3, 56.0], [23.4, 56.0], [23.5, 56.0], [23.6, 57.0], [23.7, 57.0], [23.8, 57.0], [23.9, 58.0], [24.0, 58.0], [24.1, 59.0], [24.2, 59.0], [24.3, 59.0], [24.4, 59.0], [24.5, 60.0], [24.6, 60.0], [24.7, 60.0], [24.8, 61.0], [24.9, 61.0], [25.0, 61.0], [25.1, 62.0], [25.2, 62.0], [25.3, 62.0], [25.4, 63.0], [25.5, 63.0], [25.6, 63.0], [25.7, 63.0], [25.8, 64.0], [25.9, 64.0], [26.0, 65.0], [26.1, 65.0], [26.2, 65.0], [26.3, 65.0], [26.4, 66.0], [26.5, 66.0], [26.6, 66.0], [26.7, 67.0], [26.8, 67.0], [26.9, 67.0], [27.0, 68.0], [27.1, 68.0], [27.2, 69.0], [27.3, 69.0], [27.4, 70.0], [27.5, 70.0], [27.6, 70.0], [27.7, 71.0], [27.8, 71.0], [27.9, 71.0], [28.0, 71.0], [28.1, 72.0], [28.2, 72.0], [28.3, 72.0], [28.4, 73.0], [28.5, 73.0], [28.6, 73.0], [28.7, 74.0], [28.8, 75.0], [28.9, 75.0], [29.0, 76.0], [29.1, 76.0], [29.2, 76.0], [29.3, 76.0], [29.4, 77.0], [29.5, 77.0], [29.6, 78.0], [29.7, 78.0], [29.8, 79.0], [29.9, 79.0], [30.0, 79.0], [30.1, 80.0], [30.2, 80.0], [30.3, 80.0], [30.4, 80.0], [30.5, 81.0], [30.6, 81.0], [30.7, 81.0], [30.8, 82.0], [30.9, 82.0], [31.0, 83.0], [31.1, 83.0], [31.2, 84.0], [31.3, 84.0], [31.4, 85.0], [31.5, 85.0], [31.6, 86.0], [31.7, 86.0], [31.8, 86.0], [31.9, 87.0], [32.0, 87.0], [32.1, 87.0], [32.2, 88.0], [32.3, 88.0], [32.4, 88.0], [32.5, 89.0], [32.6, 89.0], [32.7, 90.0], [32.8, 90.0], [32.9, 90.0], [33.0, 91.0], [33.1, 91.0], [33.2, 91.0], [33.3, 92.0], [33.4, 93.0], [33.5, 93.0], [33.6, 94.0], [33.7, 94.0], [33.8, 94.0], [33.9, 95.0], [34.0, 95.0], [34.1, 96.0], [34.2, 96.0], [34.3, 97.0], [34.4, 97.0], [34.5, 98.0], [34.6, 98.0], [34.7, 98.0], [34.8, 99.0], [34.9, 99.0], [35.0, 100.0], [35.1, 101.0], [35.2, 102.0], [35.3, 102.0], [35.4, 102.0], [35.5, 102.0], [35.6, 103.0], [35.7, 104.0], [35.8, 104.0], [35.9, 105.0], [36.0, 105.0], [36.1, 106.0], [36.2, 107.0], [36.3, 107.0], [36.4, 108.0], [36.5, 108.0], [36.6, 109.0], [36.7, 109.0], [36.8, 109.0], [36.9, 110.0], [37.0, 110.0], [37.1, 111.0], [37.2, 111.0], [37.3, 112.0], [37.4, 112.0], [37.5, 113.0], [37.6, 114.0], [37.7, 114.0], [37.8, 115.0], [37.9, 116.0], [38.0, 117.0], [38.1, 117.0], [38.2, 118.0], [38.3, 119.0], [38.4, 120.0], [38.5, 121.0], [38.6, 122.0], [38.7, 123.0], [38.8, 123.0], [38.9, 124.0], [39.0, 125.0], [39.1, 126.0], [39.2, 127.0], [39.3, 128.0], [39.4, 129.0], [39.5, 129.0], [39.6, 130.0], [39.7, 131.0], [39.8, 131.0], [39.9, 132.0], [40.0, 132.0], [40.1, 133.0], [40.2, 134.0], [40.3, 135.0], [40.4, 135.0], [40.5, 136.0], [40.6, 136.0], [40.7, 137.0], [40.8, 138.0], [40.9, 139.0], [41.0, 140.0], [41.1, 141.0], [41.2, 142.0], [41.3, 143.0], [41.4, 144.0], [41.5, 145.0], [41.6, 146.0], [41.7, 147.0], [41.8, 148.0], [41.9, 149.0], [42.0, 150.0], [42.1, 151.0], [42.2, 151.0], [42.3, 152.0], [42.4, 153.0], [42.5, 155.0], [42.6, 155.0], [42.7, 156.0], [42.8, 158.0], [42.9, 158.0], [43.0, 160.0], [43.1, 161.0], [43.2, 162.0], [43.3, 163.0], [43.4, 165.0], [43.5, 166.0], [43.6, 167.0], [43.7, 168.0], [43.8, 170.0], [43.9, 171.0], [44.0, 173.0], [44.1, 174.0], [44.2, 175.0], [44.3, 178.0], [44.4, 179.0], [44.5, 180.0], [44.6, 182.0], [44.7, 184.0], [44.8, 185.0], [44.9, 187.0], [45.0, 189.0], [45.1, 190.0], [45.2, 191.0], [45.3, 193.0], [45.4, 195.0], [45.5, 198.0], [45.6, 199.0], [45.7, 201.0], [45.8, 202.0], [45.9, 203.0], [46.0, 204.0], [46.1, 205.0], [46.2, 207.0], [46.3, 209.0], [46.4, 211.0], [46.5, 212.0], [46.6, 213.0], [46.7, 214.0], [46.8, 218.0], [46.9, 219.0], [47.0, 221.0], [47.1, 223.0], [47.2, 225.0], [47.3, 227.0], [47.4, 228.0], [47.5, 230.0], [47.6, 232.0], [47.7, 233.0], [47.8, 235.0], [47.9, 238.0], [48.0, 240.0], [48.1, 244.0], [48.2, 246.0], [48.3, 246.0], [48.4, 247.0], [48.5, 250.0], [48.6, 251.0], [48.7, 253.0], [48.8, 254.0], [48.9, 256.0], [49.0, 257.0], [49.1, 258.0], [49.2, 262.0], [49.3, 264.0], [49.4, 266.0], [49.5, 270.0], [49.6, 272.0], [49.7, 273.0], [49.8, 275.0], [49.9, 277.0], [50.0, 279.0], [50.1, 283.0], [50.2, 285.0], [50.3, 287.0], [50.4, 290.0], [50.5, 291.0], [50.6, 292.0], [50.7, 295.0], [50.8, 298.0], [50.9, 303.0], [51.0, 306.0], [51.1, 308.0], [51.2, 311.0], [51.3, 314.0], [51.4, 316.0], [51.5, 317.0], [51.6, 319.0], [51.7, 321.0], [51.8, 323.0], [51.9, 325.0], [52.0, 329.0], [52.1, 331.0], [52.2, 335.0], [52.3, 336.0], [52.4, 338.0], [52.5, 340.0], [52.6, 341.0], [52.7, 344.0], [52.8, 349.0], [52.9, 351.0], [53.0, 354.0], [53.1, 357.0], [53.2, 362.0], [53.3, 363.0], [53.4, 365.0], [53.5, 367.0], [53.6, 370.0], [53.7, 373.0], [53.8, 377.0], [53.9, 379.0], [54.0, 382.0], [54.1, 384.0], [54.2, 387.0], [54.3, 391.0], [54.4, 394.0], [54.5, 396.0], [54.6, 399.0], [54.7, 401.0], [54.8, 402.0], [54.9, 405.0], [55.0, 409.0], [55.1, 412.0], [55.2, 413.0], [55.3, 416.0], [55.4, 418.0], [55.5, 420.0], [55.6, 428.0], [55.7, 433.0], [55.8, 436.0], [55.9, 440.0], [56.0, 442.0], [56.1, 446.0], [56.2, 447.0], [56.3, 449.0], [56.4, 449.0], [56.5, 452.0], [56.6, 454.0], [56.7, 456.0], [56.8, 459.0], [56.9, 466.0], [57.0, 470.0], [57.1, 474.0], [57.2, 477.0], [57.3, 482.0], [57.4, 485.0], [57.5, 487.0], [57.6, 490.0], [57.7, 493.0], [57.8, 496.0], [57.9, 500.0], [58.0, 503.0], [58.1, 507.0], [58.2, 510.0], [58.3, 514.0], [58.4, 516.0], [58.5, 520.0], [58.6, 522.0], [58.7, 524.0], [58.8, 526.0], [58.9, 529.0], [59.0, 533.0], [59.1, 535.0], [59.2, 538.0], [59.3, 542.0], [59.4, 543.0], [59.5, 546.0], [59.6, 548.0], [59.7, 550.0], [59.8, 555.0], [59.9, 558.0], [60.0, 561.0], [60.1, 564.0], [60.2, 568.0], [60.3, 571.0], [60.4, 575.0], [60.5, 584.0], [60.6, 587.0], [60.7, 588.0], [60.8, 592.0], [60.9, 597.0], [61.0, 600.0], [61.1, 603.0], [61.2, 610.0], [61.3, 613.0], [61.4, 616.0], [61.5, 620.0], [61.6, 623.0], [61.7, 629.0], [61.8, 631.0], [61.9, 632.0], [62.0, 635.0], [62.1, 641.0], [62.2, 645.0], [62.3, 648.0], [62.4, 651.0], [62.5, 655.0], [62.6, 657.0], [62.7, 660.0], [62.8, 662.0], [62.9, 664.0], [63.0, 668.0], [63.1, 670.0], [63.2, 673.0], [63.3, 675.0], [63.4, 678.0], [63.5, 680.0], [63.6, 684.0], [63.7, 685.0], [63.8, 689.0], [63.9, 692.0], [64.0, 696.0], [64.1, 699.0], [64.2, 704.0], [64.3, 705.0], [64.4, 710.0], [64.5, 717.0], [64.6, 722.0], [64.7, 725.0], [64.8, 730.0], [64.9, 733.0], [65.0, 738.0], [65.1, 743.0], [65.2, 748.0], [65.3, 750.0], [65.4, 753.0], [65.5, 755.0], [65.6, 758.0], [65.7, 763.0], [65.8, 765.0], [65.9, 768.0], [66.0, 775.0], [66.1, 778.0], [66.2, 783.0], [66.3, 785.0], [66.4, 787.0], [66.5, 793.0], [66.6, 798.0], [66.7, 802.0], [66.8, 806.0], [66.9, 812.0], [67.0, 814.0], [67.1, 818.0], [67.2, 821.0], [67.3, 825.0], [67.4, 828.0], [67.5, 832.0], [67.6, 835.0], [67.7, 837.0], [67.8, 844.0], [67.9, 845.0], [68.0, 851.0], [68.1, 852.0], [68.2, 859.0], [68.3, 863.0], [68.4, 869.0], [68.5, 878.0], [68.6, 881.0], [68.7, 885.0], [68.8, 890.0], [68.9, 894.0], [69.0, 898.0], [69.1, 902.0], [69.2, 907.0], [69.3, 912.0], [69.4, 916.0], [69.5, 920.0], [69.6, 924.0], [69.7, 929.0], [69.8, 933.0], [69.9, 940.0], [70.0, 944.0], [70.1, 948.0], [70.2, 953.0], [70.3, 959.0], [70.4, 962.0], [70.5, 966.0], [70.6, 968.0], [70.7, 979.0], [70.8, 981.0], [70.9, 988.0], [71.0, 993.0], [71.1, 1002.0], [71.2, 1004.0], [71.3, 1010.0], [71.4, 1012.0], [71.5, 1014.0], [71.6, 1015.0], [71.7, 1017.0], [71.8, 1018.0], [71.9, 1019.0], [72.0, 1019.0], [72.1, 1020.0], [72.2, 1021.0], [72.3, 1022.0], [72.4, 1022.0], [72.5, 1022.0], [72.6, 1023.0], [72.7, 1023.0], [72.8, 1023.0], [72.9, 1024.0], [73.0, 1024.0], [73.1, 1025.0], [73.2, 1025.0], [73.3, 1026.0], [73.4, 1026.0], [73.5, 1027.0], [73.6, 1027.0], [73.7, 1027.0], [73.8, 1028.0], [73.9, 1028.0], [74.0, 1029.0], [74.1, 1029.0], [74.2, 1029.0], [74.3, 1030.0], [74.4, 1030.0], [74.5, 1030.0], [74.6, 1031.0], [74.7, 1031.0], [74.8, 1031.0], [74.9, 1032.0], [75.0, 1032.0], [75.1, 1032.0], [75.2, 1033.0], [75.3, 1033.0], [75.4, 1033.0], [75.5, 1034.0], [75.6, 1034.0], [75.7, 1034.0], [75.8, 1034.0], [75.9, 1034.0], [76.0, 1035.0], [76.1, 1035.0], [76.2, 1035.0], [76.3, 1035.0], [76.4, 1036.0], [76.5, 1036.0], [76.6, 1036.0], [76.7, 1036.0], [76.8, 1037.0], [76.9, 1037.0], [77.0, 1037.0], [77.1, 1037.0], [77.2, 1037.0], [77.3, 1037.0], [77.4, 1038.0], [77.5, 1038.0], [77.6, 1038.0], [77.7, 1038.0], [77.8, 1038.0], [77.9, 1039.0], [78.0, 1039.0], [78.1, 1039.0], [78.2, 1039.0], [78.3, 1040.0], [78.4, 1040.0], [78.5, 1040.0], [78.6, 1040.0], [78.7, 1041.0], [78.8, 1041.0], [78.9, 1041.0], [79.0, 1042.0], [79.1, 1042.0], [79.2, 1042.0], [79.3, 1042.0], [79.4, 1042.0], [79.5, 1042.0], [79.6, 1043.0], [79.7, 1043.0], [79.8, 1043.0], [79.9, 1043.0], [80.0, 1043.0], [80.1, 1044.0], [80.2, 1044.0], [80.3, 1044.0], [80.4, 1044.0], [80.5, 1044.0], [80.6, 1044.0], [80.7, 1045.0], [80.8, 1045.0], [80.9, 1045.0], [81.0, 1045.0], [81.1, 1046.0], [81.2, 1046.0], [81.3, 1046.0], [81.4, 1046.0], [81.5, 1046.0], [81.6, 1046.0], [81.7, 1047.0], [81.8, 1047.0], [81.9, 1047.0], [82.0, 1048.0], [82.1, 1048.0], [82.2, 1048.0], [82.3, 1048.0], [82.4, 1048.0], [82.5, 1049.0], [82.6, 1049.0], [82.7, 1049.0], [82.8, 1049.0], [82.9, 1050.0], [83.0, 1050.0], [83.1, 1050.0], [83.2, 1050.0], [83.3, 1051.0], [83.4, 1051.0], [83.5, 1051.0], [83.6, 1051.0], [83.7, 1052.0], [83.8, 1052.0], [83.9, 1052.0], [84.0, 1052.0], [84.1, 1052.0], [84.2, 1053.0], [84.3, 1053.0], [84.4, 1053.0], [84.5, 1053.0], [84.6, 1053.0], [84.7, 1053.0], [84.8, 1053.0], [84.9, 1054.0], [85.0, 1054.0], [85.1, 1054.0], [85.2, 1055.0], [85.3, 1055.0], [85.4, 1055.0], [85.5, 1055.0], [85.6, 1056.0], [85.7, 1056.0], [85.8, 1056.0], [85.9, 1056.0], [86.0, 1056.0], [86.1, 1057.0], [86.2, 1057.0], [86.3, 1057.0], [86.4, 1058.0], [86.5, 1058.0], [86.6, 1059.0], [86.7, 1059.0], [86.8, 1059.0], [86.9, 1059.0], [87.0, 1060.0], [87.1, 1060.0], [87.2, 1060.0], [87.3, 1061.0], [87.4, 1061.0], [87.5, 1062.0], [87.6, 1062.0], [87.7, 1063.0], [87.8, 1063.0], [87.9, 1064.0], [88.0, 1064.0], [88.1, 1064.0], [88.2, 1064.0], [88.3, 1065.0], [88.4, 1065.0], [88.5, 1065.0], [88.6, 1066.0], [88.7, 1066.0], [88.8, 1066.0], [88.9, 1066.0], [89.0, 1067.0], [89.1, 1067.0], [89.2, 1068.0], [89.3, 1068.0], [89.4, 1068.0], [89.5, 1069.0], [89.6, 1069.0], [89.7, 1069.0], [89.8, 1070.0], [89.9, 1070.0], [90.0, 1070.0], [90.1, 1071.0], [90.2, 1071.0], [90.3, 1071.0], [90.4, 1071.0], [90.5, 1072.0], [90.6, 1073.0], [90.7, 1073.0], [90.8, 1074.0], [90.9, 1074.0], [91.0, 1075.0], [91.1, 1076.0], [91.2, 1076.0], [91.3, 1077.0], [91.4, 1077.0], [91.5, 1078.0], [91.6, 1078.0], [91.7, 1078.0], [91.8, 1079.0], [91.9, 1079.0], [92.0, 1080.0], [92.1, 1080.0], [92.2, 1081.0], [92.3, 1081.0], [92.4, 1081.0], [92.5, 1082.0], [92.6, 1083.0], [92.7, 1083.0], [92.8, 1084.0], [92.9, 1084.0], [93.0, 1085.0], [93.1, 1086.0], [93.2, 1087.0], [93.3, 1088.0], [93.4, 1089.0], [93.5, 1090.0], [93.6, 1090.0], [93.7, 1091.0], [93.8, 1091.0], [93.9, 1092.0], [94.0, 1093.0], [94.1, 1094.0], [94.2, 1094.0], [94.3, 1095.0], [94.4, 1096.0], [94.5, 1097.0], [94.6, 1098.0], [94.7, 1099.0], [94.8, 1100.0], [94.9, 1101.0], [95.0, 1101.0], [95.1, 1102.0], [95.2, 1103.0], [95.3, 1105.0], [95.4, 1107.0], [95.5, 1108.0], [95.6, 1110.0], [95.7, 1111.0], [95.8, 1112.0], [95.9, 1114.0], [96.0, 1115.0], [96.1, 1116.0], [96.2, 1119.0], [96.3, 1122.0], [96.4, 1124.0], [96.5, 1125.0], [96.6, 1127.0], [96.7, 1129.0], [96.8, 1132.0], [96.9, 1134.0], [97.0, 1137.0], [97.1, 1141.0], [97.2, 1146.0], [97.3, 1151.0], [97.4, 1157.0], [97.5, 1162.0], [97.6, 1168.0], [97.7, 1174.0], [97.8, 1185.0], [97.9, 1200.0], [98.0, 1214.0], [98.1, 1235.0], [98.2, 1249.0], [98.3, 1256.0], [98.4, 1277.0], [98.5, 1314.0], [98.6, 1362.0], [98.7, 1393.0], [98.8, 1443.0], [98.9, 1460.0], [99.0, 1499.0], [99.1, 1522.0], [99.2, 1547.0], [99.3, 1654.0], [99.4, 1731.0], [99.5, 1779.0], [99.6, 1820.0], [99.7, 1873.0], [99.8, 2006.0], [99.9, 2090.0], [100.0, 2337.0]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "maxX": 100.0, "title": "Response Time Percentiles"}},
        getOptions: function() {
            return {
                series: {
                    points: { show: false }
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendResponseTimePercentiles'
                },
                xaxis: {
                    tickDecimals: 1,
                    axisLabel: "Percentiles",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Percentile value in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s : %x.2 percentile was %y ms"
                },
                selection: { mode: "xy" },
            };
        },
        createGraph: function() {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesResponseTimePercentiles"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotResponseTimesPercentiles"), dataset, options);
            // setup overview
            $.plot($("#overviewResponseTimesPercentiles"), dataset, prepareOverviewOptions(options));
        }
};

/**
 * @param elementId Id of element where we display message
 */
function setEmptyGraph(elementId) {
    $(function() {
        $(elementId).text("No graph series with filter="+seriesFilter);
    });
}

// Response times percentiles
function refreshResponseTimePercentiles() {
    var infos = responseTimePercentilesInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyResponseTimePercentiles");
        return;
    }
    if (isGraph($("#flotResponseTimesPercentiles"))){
        infos.createGraph();
    } else {
        var choiceContainer = $("#choicesResponseTimePercentiles");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotResponseTimesPercentiles", "#overviewResponseTimesPercentiles");
        $('#bodyResponseTimePercentiles .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
}

var responseTimeDistributionInfos = {
        data: {"result": {"minY": 1.0, "minX": 0.0, "maxY": 2154.0, "series": [{"data": [[0.0, 2154.0], [2100.0, 4.0], [2300.0, 1.0], [2200.0, 1.0], [600.0, 193.0], [700.0, 155.0], [800.0, 147.0], [200.0, 321.0], [900.0, 127.0], [1000.0, 1458.0], [1100.0, 195.0], [1200.0, 35.0], [300.0, 235.0], [1300.0, 15.0], [1400.0, 19.0], [1500.0, 17.0], [1600.0, 4.0], [100.0, 658.0], [400.0, 202.0], [1700.0, 11.0], [1800.0, 13.0], [1900.0, 3.0], [2000.0, 7.0], [500.0, 191.0]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 100, "maxX": 2300.0, "title": "Response Time Distribution"}},
        getOptions: function() {
            var granularity = this.data.result.granularity;
            return {
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendResponseTimeDistribution'
                },
                xaxis:{
                    axisLabel: "Response times in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Number of responses",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                bars : {
                    show: true,
                    barWidth: this.data.result.granularity
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: function(label, xval, yval, flotItem){
                        return yval + " responses for " + label + " were between " + xval + " and " + (xval + granularity) + " ms";
                    }
                }
            };
        },
        createGraph: function() {
            var data = this.data;
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotResponseTimeDistribution"), prepareData(data.result.series, $("#choicesResponseTimeDistribution")), options);
        }

};

// Response time distribution
function refreshResponseTimeDistribution() {
    var infos = responseTimeDistributionInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyResponseTimeDistribution");
        return;
    }
    if (isGraph($("#flotResponseTimeDistribution"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesResponseTimeDistribution");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        $('#footerResponseTimeDistribution .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};


var syntheticResponseTimeDistributionInfos = {
        data: {"result": {"minY": 1.0, "minX": 1.0, "ticks": [[0, "Requests having \nresponse time <= 500ms"], [1, "Requests having \nresponse time > 500ms and <= 1,500ms"], [2, "Requests having \nresponse time > 1,500ms"], [3, "Requests in error"]], "maxY": 6163.0, "series": [{"data": [], "color": "#9ACD32", "isOverall": false, "label": "Requests having \nresponse time <= 500ms", "isController": false}, {"data": [[1.0, 1.0]], "color": "yellow", "isOverall": false, "label": "Requests having \nresponse time > 500ms and <= 1,500ms", "isController": false}, {"data": [[2.0, 2.0]], "color": "orange", "isOverall": false, "label": "Requests having \nresponse time > 1,500ms", "isController": false}, {"data": [[3.0, 6163.0]], "color": "#FF6347", "isOverall": false, "label": "Requests in error", "isController": false}], "supportsControllersDiscrimination": false, "maxX": 3.0, "title": "Synthetic Response Times Distribution"}},
        getOptions: function() {
            return {
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendSyntheticResponseTimeDistribution'
                },
                xaxis:{
                    axisLabel: "Response times ranges",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                    tickLength:0,
                    min:-0.5,
                    max:3.5
                },
                yaxis: {
                    axisLabel: "Number of responses",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                bars : {
                    show: true,
                    align: "center",
                    barWidth: 0.25,
                    fill:.75
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: function(label, xval, yval, flotItem){
                        return yval + " " + label;
                    }
                }
            };
        },
        createGraph: function() {
            var data = this.data;
            var options = this.getOptions();
            prepareOptions(options, data);
            options.xaxis.ticks = data.result.ticks;
            $.plot($("#flotSyntheticResponseTimeDistribution"), prepareData(data.result.series, $("#choicesSyntheticResponseTimeDistribution")), options);
        }

};

// Response time distribution
function refreshSyntheticResponseTimeDistribution() {
    var infos = syntheticResponseTimeDistributionInfos;
    prepareSeries(infos.data, true);
    if (isGraph($("#flotSyntheticResponseTimeDistribution"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesSyntheticResponseTimeDistribution");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        $('#footerSyntheticResponseTimeDistribution .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var activeThreadsOverTimeInfos = {
        data: {"result": {"minY": 49.77051643192489, "minX": 1.76294868E12, "maxY": 50.0, "series": [{"data": [[1.76294868E12, 50.0], [1.76294874E12, 49.77051643192489]], "isOverall": false, "label": "Gradual Load Test", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76294874E12, "title": "Active Threads Over Time"}},
        getOptions: function() {
            return {
                series: {
                    stack: true,
                    lines: {
                        show: true,
                        fill: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Number of active threads",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20
                },
                legend: {
                    noColumns: 6,
                    show: true,
                    container: '#legendActiveThreadsOverTime'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                selection: {
                    mode: 'xy'
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s : At %x there were %y active threads"
                }
            };
        },
        createGraph: function() {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesActiveThreadsOverTime"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotActiveThreadsOverTime"), dataset, options);
            // setup overview
            $.plot($("#overviewActiveThreadsOverTime"), dataset, prepareOverviewOptions(options));
        }
};

// Active Threads Over Time
function refreshActiveThreadsOverTime(fixTimestamps) {
    var infos = activeThreadsOverTimeInfos;
    prepareSeries(infos.data);
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotActiveThreadsOverTime"))) {
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesActiveThreadsOverTime");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotActiveThreadsOverTime", "#overviewActiveThreadsOverTime");
        $('#footerActiveThreadsOverTime .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var timeVsThreadsInfos = {
        data: {"result": {"minY": 82.5, "minX": 1.0, "maxY": 1055.0, "series": [{"data": [[33.0, 1035.0], [32.0, 748.0], [2.0, 657.0], [34.0, 1039.0], [37.0, 1034.0], [36.0, 759.0], [39.0, 415.0], [38.0, 1046.0], [41.0, 637.0], [40.0, 378.0], [43.0, 806.0], [42.0, 1055.0], [45.0, 317.0], [44.0, 335.0], [47.0, 82.5], [49.0, 456.75000000000006], [48.0, 1046.0], [3.0, 830.0], [50.0, 482.94111874386715], [4.0, 495.0], [5.0, 522.0], [6.0, 533.0], [7.0, 533.0], [8.0, 647.0], [9.0, 820.0], [10.0, 1019.0], [11.0, 523.0], [12.0, 634.0], [13.0, 1013.0], [15.0, 670.0], [16.0, 1021.0], [1.0, 726.0], [17.0, 754.0], [18.0, 1021.0], [19.0, 835.0], [20.0, 816.0], [21.0, 356.0], [23.0, 711.0], [24.0, 1017.0], [25.0, 367.0], [27.0, 470.0], [28.0, 1028.0], [29.0, 328.0], [31.0, 272.0]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}, {"data": [[49.80181641258514, 484.405449237756]], "isOverall": false, "label": "Complete Reservation (Saga Test)-Aggregated", "isController": false}], "supportsControllersDiscrimination": true, "maxX": 50.0, "title": "Time VS Threads"}},
        getOptions: function() {
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    axisLabel: "Number of active threads",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Average response times in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20
                },
                legend: { noColumns: 2,show: true, container: '#legendTimeVsThreads' },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s: At %x.2 active threads, Average response time was %y.2 ms"
                }
            };
        },
        createGraph: function() {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesTimeVsThreads"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotTimesVsThreads"), dataset, options);
            // setup overview
            $.plot($("#overviewTimesVsThreads"), dataset, prepareOverviewOptions(options));
        }
};

// Time vs threads
function refreshTimeVsThreads(){
    var infos = timeVsThreadsInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyTimeVsThreads");
        return;
    }
    if(isGraph($("#flotTimesVsThreads"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesTimeVsThreads");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotTimesVsThreads", "#overviewTimesVsThreads");
        $('#footerTimeVsThreads .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var bytesThroughputOverTimeInfos = {
        data : {"result": {"minY": 3300.7166666666667, "minX": 1.76294868E12, "maxY": 46505.0, "series": [{"data": [[1.76294868E12, 3300.7166666666667], [1.76294874E12, 20612.583333333332]], "isOverall": false, "label": "Bytes received per second", "isController": false}, {"data": [[1.76294868E12, 7344.733333333334], [1.76294874E12, 46505.0]], "isOverall": false, "label": "Bytes sent per second", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76294874E12, "title": "Bytes Throughput Over Time"}},
        getOptions : function(){
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity) ,
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Bytes / sec",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendBytesThroughputOverTime'
                },
                selection: {
                    mode: "xy"
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s at %x was %y"
                }
            };
        },
        createGraph : function() {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesBytesThroughputOverTime"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotBytesThroughputOverTime"), dataset, options);
            // setup overview
            $.plot($("#overviewBytesThroughputOverTime"), dataset, prepareOverviewOptions(options));
        }
};

// Bytes throughput Over Time
function refreshBytesThroughputOverTime(fixTimestamps) {
    var infos = bytesThroughputOverTimeInfos;
    prepareSeries(infos.data);
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotBytesThroughputOverTime"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesBytesThroughputOverTime");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotBytesThroughputOverTime", "#overviewBytesThroughputOverTime");
        $('#footerBytesThroughputOverTime .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
}

var responseTimesOverTimeInfos = {
        data: {"result": {"minY": 460.9573708920193, "minX": 1.76294868E12, "maxY": 632.872770511297, "series": [{"data": [[1.76294868E12, 632.872770511297], [1.76294874E12, 460.9573708920193]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76294874E12, "title": "Response Time Over Time"}},
        getOptions: function(){
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Average response time in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendResponseTimesOverTime'
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s : at %x Average response time was %y ms"
                }
            };
        },
        createGraph: function() {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesResponseTimesOverTime"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotResponseTimesOverTime"), dataset, options);
            // setup overview
            $.plot($("#overviewResponseTimesOverTime"), dataset, prepareOverviewOptions(options));
        }
};

// Response Times Over Time
function refreshResponseTimeOverTime(fixTimestamps) {
    var infos = responseTimesOverTimeInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyResponseTimeOverTime");
        return;
    }
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotResponseTimesOverTime"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesResponseTimesOverTime");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotResponseTimesOverTime", "#overviewResponseTimesOverTime");
        $('#footerResponseTimesOverTime .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var latenciesOverTimeInfos = {
        data: {"result": {"minY": 459.7767136150227, "minX": 1.76294868E12, "maxY": 628.7110582639714, "series": [{"data": [[1.76294868E12, 628.7110582639714], [1.76294874E12, 459.7767136150227]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76294874E12, "title": "Latencies Over Time"}},
        getOptions: function() {
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Average response latencies in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendLatenciesOverTime'
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s : at %x Average latency was %y ms"
                }
            };
        },
        createGraph: function () {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesLatenciesOverTime"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotLatenciesOverTime"), dataset, options);
            // setup overview
            $.plot($("#overviewLatenciesOverTime"), dataset, prepareOverviewOptions(options));
        }
};

// Latencies Over Time
function refreshLatenciesOverTime(fixTimestamps) {
    var infos = latenciesOverTimeInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyLatenciesOverTime");
        return;
    }
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotLatenciesOverTime"))) {
        infos.createGraph();
    }else {
        var choiceContainer = $("#choicesLatenciesOverTime");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotLatenciesOverTime", "#overviewLatenciesOverTime");
        $('#footerLatenciesOverTime .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var connectTimeOverTimeInfos = {
        data: {"result": {"minY": 1.6775586854460123, "minX": 1.76294868E12, "maxY": 2.2033293697978595, "series": [{"data": [[1.76294868E12, 2.2033293697978595], [1.76294874E12, 1.6775586854460123]], "isOverall": false, "label": "Complete Reservation (Saga Test)", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76294874E12, "title": "Connect Time Over Time"}},
        getOptions: function() {
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getConnectTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Average Connect Time in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendConnectTimeOverTime'
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s : at %x Average connect time was %y ms"
                }
            };
        },
        createGraph: function () {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesConnectTimeOverTime"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotConnectTimeOverTime"), dataset, options);
            // setup overview
            $.plot($("#overviewConnectTimeOverTime"), dataset, prepareOverviewOptions(options));
        }
};

// Connect Time Over Time
function refreshConnectTimeOverTime(fixTimestamps) {
    var infos = connectTimeOverTimeInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyConnectTimeOverTime");
        return;
    }
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotConnectTimeOverTime"))) {
        infos.createGraph();
    }else {
        var choiceContainer = $("#choicesConnectTimeOverTime");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotConnectTimeOverTime", "#overviewConnectTimeOverTime");
        $('#footerConnectTimeOverTime .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var responseTimePercentilesOverTimeInfos = {
        data: {"result": {"minY": 1456.0, "minX": 1.76294868E12, "maxY": 2006.0, "series": [{"data": [[1.76294868E12, 2006.0]], "isOverall": false, "label": "Max", "isController": false}, {"data": [[1.76294868E12, 2006.0]], "isOverall": false, "label": "90th percentile", "isController": false}, {"data": [[1.76294868E12, 2006.0]], "isOverall": false, "label": "99th percentile", "isController": false}, {"data": [[1.76294868E12, 2006.0]], "isOverall": false, "label": "95th percentile", "isController": false}, {"data": [[1.76294868E12, 1456.0]], "isOverall": false, "label": "Min", "isController": false}, {"data": [[1.76294868E12, 1771.0]], "isOverall": false, "label": "Median", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76294868E12, "title": "Response Time Percentiles Over Time (successful requests only)"}},
        getOptions: function() {
            return {
                series: {
                    lines: {
                        show: true,
                        fill: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Response Time in ms",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: '#legendResponseTimePercentilesOverTime'
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s : at %x Response time was %y ms"
                }
            };
        },
        createGraph: function () {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesResponseTimePercentilesOverTime"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotResponseTimePercentilesOverTime"), dataset, options);
            // setup overview
            $.plot($("#overviewResponseTimePercentilesOverTime"), dataset, prepareOverviewOptions(options));
        }
};

// Response Time Percentiles Over Time
function refreshResponseTimePercentilesOverTime(fixTimestamps) {
    var infos = responseTimePercentilesOverTimeInfos;
    prepareSeries(infos.data);
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotResponseTimePercentilesOverTime"))) {
        infos.createGraph();
    }else {
        var choiceContainer = $("#choicesResponseTimePercentilesOverTime");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotResponseTimePercentilesOverTime", "#overviewResponseTimePercentilesOverTime");
        $('#footerResponseTimePercentilesOverTime .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};


var responseTimeVsRequestInfos = {
    data: {"result": {"minY": 51.0, "minX": 22.0, "maxY": 1771.0, "series": [{"data": [[96.0, 1771.0]], "isOverall": false, "label": "Successes", "isController": false}, {"data": [[58.0, 1537.0], [69.0, 467.0], [84.0, 538.0], [89.0, 452.0], [91.0, 287.0], [88.0, 711.5], [95.0, 240.0], [92.0, 409.5], [93.0, 253.0], [96.0, 828.0], [99.0, 309.0], [101.0, 389.5], [102.0, 213.0], [105.0, 349.0], [104.0, 279.0], [106.0, 346.5], [107.0, 243.0], [108.0, 248.5], [110.0, 309.0], [111.0, 261.0], [109.0, 184.0], [114.0, 326.5], [113.0, 220.5], [117.0, 82.0], [119.0, 77.0], [118.0, 155.0], [116.0, 154.5], [123.0, 119.0], [122.0, 103.0], [121.0, 98.0], [124.0, 152.5], [125.0, 155.0], [130.0, 192.0], [133.0, 88.0], [128.0, 51.0], [141.0, 99.0], [136.0, 199.0], [22.0, 1495.0]], "isOverall": false, "label": "Failures", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 1000, "maxX": 141.0, "title": "Response Time Vs Request"}},
    getOptions: function() {
        return {
            series: {
                lines: {
                    show: false
                },
                points: {
                    show: true
                }
            },
            xaxis: {
                axisLabel: "Global number of requests per second",
                axisLabelUseCanvas: true,
                axisLabelFontSizePixels: 12,
                axisLabelFontFamily: 'Verdana, Arial',
                axisLabelPadding: 20,
            },
            yaxis: {
                axisLabel: "Median Response Time in ms",
                axisLabelUseCanvas: true,
                axisLabelFontSizePixels: 12,
                axisLabelFontFamily: 'Verdana, Arial',
                axisLabelPadding: 20,
            },
            legend: {
                noColumns: 2,
                show: true,
                container: '#legendResponseTimeVsRequest'
            },
            selection: {
                mode: 'xy'
            },
            grid: {
                hoverable: true // IMPORTANT! this is needed for tooltip to work
            },
            tooltip: true,
            tooltipOpts: {
                content: "%s : Median response time at %x req/s was %y ms"
            },
            colors: ["#9ACD32", "#FF6347"]
        };
    },
    createGraph: function () {
        var data = this.data;
        var dataset = prepareData(data.result.series, $("#choicesResponseTimeVsRequest"));
        var options = this.getOptions();
        prepareOptions(options, data);
        $.plot($("#flotResponseTimeVsRequest"), dataset, options);
        // setup overview
        $.plot($("#overviewResponseTimeVsRequest"), dataset, prepareOverviewOptions(options));

    }
};

// Response Time vs Request
function refreshResponseTimeVsRequest() {
    var infos = responseTimeVsRequestInfos;
    prepareSeries(infos.data);
    if (isGraph($("#flotResponseTimeVsRequest"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesResponseTimeVsRequest");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotResponseTimeVsRequest", "#overviewResponseTimeVsRequest");
        $('#footerResponseRimeVsRequest .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};


var latenciesVsRequestInfos = {
    data: {"result": {"minY": 50.5, "minX": 22.0, "maxY": 1748.0, "series": [{"data": [[96.0, 1748.0]], "isOverall": false, "label": "Successes", "isController": false}, {"data": [[58.0, 1533.5], [69.0, 466.5], [84.0, 537.0], [89.0, 451.5], [91.0, 286.0], [88.0, 711.0], [95.0, 239.0], [92.0, 409.0], [93.0, 253.0], [96.0, 827.0], [99.0, 308.0], [101.0, 389.0], [102.0, 212.5], [105.0, 348.0], [104.0, 279.0], [106.0, 346.5], [107.0, 243.0], [108.0, 248.5], [110.0, 309.0], [111.0, 260.5], [109.0, 184.0], [114.0, 325.5], [113.0, 220.5], [117.0, 82.0], [119.0, 77.0], [118.0, 154.5], [116.0, 154.5], [123.0, 118.0], [122.0, 103.0], [121.0, 98.0], [124.0, 152.5], [125.0, 155.0], [130.0, 191.5], [133.0, 88.0], [128.0, 50.5], [141.0, 98.0], [136.0, 199.0], [22.0, 1492.0]], "isOverall": false, "label": "Failures", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 1000, "maxX": 141.0, "title": "Latencies Vs Request"}},
    getOptions: function() {
        return{
            series: {
                lines: {
                    show: false
                },
                points: {
                    show: true
                }
            },
            xaxis: {
                axisLabel: "Global number of requests per second",
                axisLabelUseCanvas: true,
                axisLabelFontSizePixels: 12,
                axisLabelFontFamily: 'Verdana, Arial',
                axisLabelPadding: 20,
            },
            yaxis: {
                axisLabel: "Median Latency in ms",
                axisLabelUseCanvas: true,
                axisLabelFontSizePixels: 12,
                axisLabelFontFamily: 'Verdana, Arial',
                axisLabelPadding: 20,
            },
            legend: { noColumns: 2,show: true, container: '#legendLatencyVsRequest' },
            selection: {
                mode: 'xy'
            },
            grid: {
                hoverable: true // IMPORTANT! this is needed for tooltip to work
            },
            tooltip: true,
            tooltipOpts: {
                content: "%s : Median Latency time at %x req/s was %y ms"
            },
            colors: ["#9ACD32", "#FF6347"]
        };
    },
    createGraph: function () {
        var data = this.data;
        var dataset = prepareData(data.result.series, $("#choicesLatencyVsRequest"));
        var options = this.getOptions();
        prepareOptions(options, data);
        $.plot($("#flotLatenciesVsRequest"), dataset, options);
        // setup overview
        $.plot($("#overviewLatenciesVsRequest"), dataset, prepareOverviewOptions(options));
    }
};

// Latencies vs Request
function refreshLatenciesVsRequest() {
        var infos = latenciesVsRequestInfos;
        prepareSeries(infos.data);
        if(isGraph($("#flotLatenciesVsRequest"))){
            infos.createGraph();
        }else{
            var choiceContainer = $("#choicesLatencyVsRequest");
            createLegend(choiceContainer, infos);
            infos.createGraph();
            setGraphZoomable("#flotLatenciesVsRequest", "#overviewLatenciesVsRequest");
            $('#footerLatenciesVsRequest .legendColorBox > div').each(function(i){
                $(this).clone().prependTo(choiceContainer.find("li").eq(i));
            });
        }
};

var hitsPerSecondInfos = {
        data: {"result": {"minY": 14.85, "minX": 1.76294868E12, "maxY": 87.91666666666667, "series": [{"data": [[1.76294868E12, 14.85], [1.76294874E12, 87.91666666666667]], "isOverall": false, "label": "hitsPerSecond", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76294874E12, "title": "Hits Per Second"}},
        getOptions: function() {
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Number of hits / sec",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: "#legendHitsPerSecond"
                },
                selection: {
                    mode : 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s at %x was %y.2 hits/sec"
                }
            };
        },
        createGraph: function createGraph() {
            var data = this.data;
            var dataset = prepareData(data.result.series, $("#choicesHitsPerSecond"));
            var options = this.getOptions();
            prepareOptions(options, data);
            $.plot($("#flotHitsPerSecond"), dataset, options);
            // setup overview
            $.plot($("#overviewHitsPerSecond"), dataset, prepareOverviewOptions(options));
        }
};

// Hits per second
function refreshHitsPerSecond(fixTimestamps) {
    var infos = hitsPerSecondInfos;
    prepareSeries(infos.data);
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if (isGraph($("#flotHitsPerSecond"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesHitsPerSecond");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotHitsPerSecond", "#overviewHitsPerSecond");
        $('#footerHitsPerSecond .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
}

var codesPerSecondInfos = {
        data: {"result": {"minY": 0.05, "minX": 1.76294868E12, "maxY": 88.75, "series": [{"data": [[1.76294868E12, 0.05]], "isOverall": false, "label": "200", "isController": false}, {"data": [[1.76294868E12, 13.966666666666667], [1.76294874E12, 88.75]], "isOverall": false, "label": "400", "isController": false}], "supportsControllersDiscrimination": false, "granularity": 60000, "maxX": 1.76294874E12, "title": "Codes Per Second"}},
        getOptions: function(){
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Number of responses / sec",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: "#legendCodesPerSecond"
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "Number of Response Codes %s at %x was %y.2 responses / sec"
                }
            };
        },
    createGraph: function() {
        var data = this.data;
        var dataset = prepareData(data.result.series, $("#choicesCodesPerSecond"));
        var options = this.getOptions();
        prepareOptions(options, data);
        $.plot($("#flotCodesPerSecond"), dataset, options);
        // setup overview
        $.plot($("#overviewCodesPerSecond"), dataset, prepareOverviewOptions(options));
    }
};

// Codes per second
function refreshCodesPerSecond(fixTimestamps) {
    var infos = codesPerSecondInfos;
    prepareSeries(infos.data);
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotCodesPerSecond"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesCodesPerSecond");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotCodesPerSecond", "#overviewCodesPerSecond");
        $('#footerCodesPerSecond .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var transactionsPerSecondInfos = {
        data: {"result": {"minY": 0.05, "minX": 1.76294868E12, "maxY": 88.75, "series": [{"data": [[1.76294868E12, 13.966666666666667], [1.76294874E12, 88.75]], "isOverall": false, "label": "Complete Reservation (Saga Test)-failure", "isController": false}, {"data": [[1.76294868E12, 0.05]], "isOverall": false, "label": "Complete Reservation (Saga Test)-success", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76294874E12, "title": "Transactions Per Second"}},
        getOptions: function(){
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Number of transactions / sec",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: "#legendTransactionsPerSecond"
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s at %x was %y transactions / sec"
                }
            };
        },
    createGraph: function () {
        var data = this.data;
        var dataset = prepareData(data.result.series, $("#choicesTransactionsPerSecond"));
        var options = this.getOptions();
        prepareOptions(options, data);
        $.plot($("#flotTransactionsPerSecond"), dataset, options);
        // setup overview
        $.plot($("#overviewTransactionsPerSecond"), dataset, prepareOverviewOptions(options));
    }
};

// Transactions per second
function refreshTransactionsPerSecond(fixTimestamps) {
    var infos = transactionsPerSecondInfos;
    prepareSeries(infos.data);
    if(infos.data.result.series.length == 0) {
        setEmptyGraph("#bodyTransactionsPerSecond");
        return;
    }
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotTransactionsPerSecond"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesTransactionsPerSecond");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotTransactionsPerSecond", "#overviewTransactionsPerSecond");
        $('#footerTransactionsPerSecond .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

var totalTPSInfos = {
        data: {"result": {"minY": 0.05, "minX": 1.76294868E12, "maxY": 88.75, "series": [{"data": [[1.76294868E12, 0.05]], "isOverall": false, "label": "Transaction-success", "isController": false}, {"data": [[1.76294868E12, 13.966666666666667], [1.76294874E12, 88.75]], "isOverall": false, "label": "Transaction-failure", "isController": false}], "supportsControllersDiscrimination": true, "granularity": 60000, "maxX": 1.76294874E12, "title": "Total Transactions Per Second"}},
        getOptions: function(){
            return {
                series: {
                    lines: {
                        show: true
                    },
                    points: {
                        show: true
                    }
                },
                xaxis: {
                    mode: "time",
                    timeformat: getTimeFormat(this.data.result.granularity),
                    axisLabel: getElapsedTimeLabel(this.data.result.granularity),
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20,
                },
                yaxis: {
                    axisLabel: "Number of transactions / sec",
                    axisLabelUseCanvas: true,
                    axisLabelFontSizePixels: 12,
                    axisLabelFontFamily: 'Verdana, Arial',
                    axisLabelPadding: 20
                },
                legend: {
                    noColumns: 2,
                    show: true,
                    container: "#legendTotalTPS"
                },
                selection: {
                    mode: 'xy'
                },
                grid: {
                    hoverable: true // IMPORTANT! this is needed for tooltip to
                                    // work
                },
                tooltip: true,
                tooltipOpts: {
                    content: "%s at %x was %y transactions / sec"
                },
                colors: ["#9ACD32", "#FF6347"]
            };
        },
    createGraph: function () {
        var data = this.data;
        var dataset = prepareData(data.result.series, $("#choicesTotalTPS"));
        var options = this.getOptions();
        prepareOptions(options, data);
        $.plot($("#flotTotalTPS"), dataset, options);
        // setup overview
        $.plot($("#overviewTotalTPS"), dataset, prepareOverviewOptions(options));
    }
};

// Total Transactions per second
function refreshTotalTPS(fixTimestamps) {
    var infos = totalTPSInfos;
    // We want to ignore seriesFilter
    prepareSeries(infos.data, false, true);
    if(fixTimestamps) {
        fixTimeStamps(infos.data.result.series, 32400000);
    }
    if(isGraph($("#flotTotalTPS"))){
        infos.createGraph();
    }else{
        var choiceContainer = $("#choicesTotalTPS");
        createLegend(choiceContainer, infos);
        infos.createGraph();
        setGraphZoomable("#flotTotalTPS", "#overviewTotalTPS");
        $('#footerTotalTPS .legendColorBox > div').each(function(i){
            $(this).clone().prependTo(choiceContainer.find("li").eq(i));
        });
    }
};

// Collapse the graph matching the specified DOM element depending the collapsed
// status
function collapse(elem, collapsed){
    if(collapsed){
        $(elem).parent().find(".fa-chevron-up").removeClass("fa-chevron-up").addClass("fa-chevron-down");
    } else {
        $(elem).parent().find(".fa-chevron-down").removeClass("fa-chevron-down").addClass("fa-chevron-up");
        if (elem.id == "bodyBytesThroughputOverTime") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshBytesThroughputOverTime(true);
            }
            document.location.href="#bytesThroughputOverTime";
        } else if (elem.id == "bodyLatenciesOverTime") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshLatenciesOverTime(true);
            }
            document.location.href="#latenciesOverTime";
        } else if (elem.id == "bodyCustomGraph") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshCustomGraph(true);
            }
            document.location.href="#responseCustomGraph";
        } else if (elem.id == "bodyConnectTimeOverTime") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshConnectTimeOverTime(true);
            }
            document.location.href="#connectTimeOverTime";
        } else if (elem.id == "bodyResponseTimePercentilesOverTime") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshResponseTimePercentilesOverTime(true);
            }
            document.location.href="#responseTimePercentilesOverTime";
        } else if (elem.id == "bodyResponseTimeDistribution") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshResponseTimeDistribution();
            }
            document.location.href="#responseTimeDistribution" ;
        } else if (elem.id == "bodySyntheticResponseTimeDistribution") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshSyntheticResponseTimeDistribution();
            }
            document.location.href="#syntheticResponseTimeDistribution" ;
        } else if (elem.id == "bodyActiveThreadsOverTime") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshActiveThreadsOverTime(true);
            }
            document.location.href="#activeThreadsOverTime";
        } else if (elem.id == "bodyTimeVsThreads") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshTimeVsThreads();
            }
            document.location.href="#timeVsThreads" ;
        } else if (elem.id == "bodyCodesPerSecond") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshCodesPerSecond(true);
            }
            document.location.href="#codesPerSecond";
        } else if (elem.id == "bodyTransactionsPerSecond") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshTransactionsPerSecond(true);
            }
            document.location.href="#transactionsPerSecond";
        } else if (elem.id == "bodyTotalTPS") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshTotalTPS(true);
            }
            document.location.href="#totalTPS";
        } else if (elem.id == "bodyResponseTimeVsRequest") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshResponseTimeVsRequest();
            }
            document.location.href="#responseTimeVsRequest";
        } else if (elem.id == "bodyLatenciesVsRequest") {
            if (isGraph($(elem).find('.flot-chart-content')) == false) {
                refreshLatenciesVsRequest();
            }
            document.location.href="#latencyVsRequest";
        }
    }
}

/*
 * Activates or deactivates all series of the specified graph (represented by id parameter)
 * depending on checked argument.
 */
function toggleAll(id, checked){
    var placeholder = document.getElementById(id);

    var cases = $(placeholder).find(':checkbox');
    cases.prop('checked', checked);
    $(cases).parent().children().children().toggleClass("legend-disabled", !checked);

    var choiceContainer;
    if ( id == "choicesBytesThroughputOverTime"){
        choiceContainer = $("#choicesBytesThroughputOverTime");
        refreshBytesThroughputOverTime(false);
    } else if(id == "choicesResponseTimesOverTime"){
        choiceContainer = $("#choicesResponseTimesOverTime");
        refreshResponseTimeOverTime(false);
    }else if(id == "choicesResponseCustomGraph"){
        choiceContainer = $("#choicesResponseCustomGraph");
        refreshCustomGraph(false);
    } else if ( id == "choicesLatenciesOverTime"){
        choiceContainer = $("#choicesLatenciesOverTime");
        refreshLatenciesOverTime(false);
    } else if ( id == "choicesConnectTimeOverTime"){
        choiceContainer = $("#choicesConnectTimeOverTime");
        refreshConnectTimeOverTime(false);
    } else if ( id == "choicesResponseTimePercentilesOverTime"){
        choiceContainer = $("#choicesResponseTimePercentilesOverTime");
        refreshResponseTimePercentilesOverTime(false);
    } else if ( id == "choicesResponseTimePercentiles"){
        choiceContainer = $("#choicesResponseTimePercentiles");
        refreshResponseTimePercentiles();
    } else if(id == "choicesActiveThreadsOverTime"){
        choiceContainer = $("#choicesActiveThreadsOverTime");
        refreshActiveThreadsOverTime(false);
    } else if ( id == "choicesTimeVsThreads"){
        choiceContainer = $("#choicesTimeVsThreads");
        refreshTimeVsThreads();
    } else if ( id == "choicesSyntheticResponseTimeDistribution"){
        choiceContainer = $("#choicesSyntheticResponseTimeDistribution");
        refreshSyntheticResponseTimeDistribution();
    } else if ( id == "choicesResponseTimeDistribution"){
        choiceContainer = $("#choicesResponseTimeDistribution");
        refreshResponseTimeDistribution();
    } else if ( id == "choicesHitsPerSecond"){
        choiceContainer = $("#choicesHitsPerSecond");
        refreshHitsPerSecond(false);
    } else if(id == "choicesCodesPerSecond"){
        choiceContainer = $("#choicesCodesPerSecond");
        refreshCodesPerSecond(false);
    } else if ( id == "choicesTransactionsPerSecond"){
        choiceContainer = $("#choicesTransactionsPerSecond");
        refreshTransactionsPerSecond(false);
    } else if ( id == "choicesTotalTPS"){
        choiceContainer = $("#choicesTotalTPS");
        refreshTotalTPS(false);
    } else if ( id == "choicesResponseTimeVsRequest"){
        choiceContainer = $("#choicesResponseTimeVsRequest");
        refreshResponseTimeVsRequest();
    } else if ( id == "choicesLatencyVsRequest"){
        choiceContainer = $("#choicesLatencyVsRequest");
        refreshLatenciesVsRequest();
    }
    var color = checked ? "black" : "#818181";
    if(choiceContainer != null) {
        choiceContainer.find("label").each(function(){
            this.style.color = color;
        });
    }
}

