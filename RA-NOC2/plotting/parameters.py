# parameters for plot
title_font = 20
axis_font = 18
tick_font = 16
title_info = {'fontsize': title_font,
            'fontweight' : "bold",
            'verticalalignment': 'baseline',
            'horizontalalignment': "center"}

EIGER_PORT = "EIGER_PORT"
EIGER_PORT_PLUS = "EIGER_PORT_PLUS"
EIGER = "EIGER"

marker_size = 13
line_width = 2.5
tick_font_inaxis = 10
haveGrid = False
showPlot = False
bar_width = 0.2
spacing = 0.03
allBar = False
freshBar = False
plus = False
normalize = False
normalizer = EIGER_PORT
tp_label = "Throughput (ops/s)"
lat_label = "Latency (ms)"

zipfs = ["0.0", "0.3", "0.7", "0.8", "0.9", "0.99", "1.1", "1.2"]

algorithms = ["READ_ATOMIC_CONST_ORT", "READ_ATOMIC_FASTOPW", "READ_ATOMIC_LIST", "READ_ATOMIC_LORA", "READ_ATOMIC_STAMP"]
saveTo = "./plots/noplus/"
# edit this if you want to change the algorithms you can plot
if plus:
    algorithms = ["READ_ATOMIC_CONST_ORT", "READ_ATOMIC_FASTOPW", "READ_ATOMIC_LIST", "READ_ATOMIC_LORA", "READ_ATOMIC_STAMP", "READ_ATOMIC_NOC"]
    saveTo = "./plots/plus/"

if normalize:
    algorithms = ["READ_ATOMIC_CONST_ORT", "READ_ATOMIC_FASTOPW", "READ_ATOMIC_LIST", "READ_ATOMIC_LORA", "READ_ATOMIC_STAMP"]
    tp_label = "Normalized Throughput"
    lat_label = "Normalized Latency"
#algorithms = ["EIGER","EIGER_PORT", "EIGER_PORT_PLUS_PLUS"]
# save the images as pdfs here
 
colors = {
    "READ_ATOMIC_CONST_ORT": "#1f77b4",
    "READ_ATOMIC_FASTOPW": "#ff7f0e",
    "READ_ATOMIC_LIST": "#2ca02c",
    "READ_ATOMIC_LORA": "#9467bd",
    "READ_ATOMIC_STAMP": "#d62728",
    "READ_ATOMIC_NOC": "#8c564b",
    "0.0" : 'gray',
    "0.3": 'coral',
    "0.7": 'maroon',
    "0.8": 'teal',
    "0.9": 'brown',
    "0.99": 'olive',
    "1.1": 'navy',
    "1.2": 'indigo'
}

markers = {
    "READ_ATOMIC_CONST_ORT": "o",      # Circle
    "READ_ATOMIC_FASTOPW": "s",        # Square
    "READ_ATOMIC_LIST": "^",           # Triangle Up
    "READ_ATOMIC_LORA": "D",           # Diamond
    "READ_ATOMIC_STAMP": "v",          # Triangle Down
    "READ_ATOMIC_NOC": "P",            # Plus (filled)
    "0.0" : 'o',
    "0.3": 's',
    "0.7": 'v',
    "0.8": 'x',
    "0.9": 'd',
    "0.99": 'p',
    "1.1": 'h',
    "1.2": '8'
}
bar_line_width = 1.5

is_full = {
    "EIGER": False,
    "EIGER_PORT": False,
    "EIGER_PORT_PLUS": False,
    "EIGER_PORT_PLUS_PLUS": True,
    "0.0" : False,
    "0.8": False,
    "0.99": False,
    "1.1" : True,
}
bar_markers = {
    "EIGER": "++++",
    "EIGER_PORT": "/",
    "EIGER_PORT_PLUS": "+",
    "EIGER_PORT_PLUS_PLUS": "",
    "0.0" : ".",
    "0.8": "+",
    "0.99": "/",
    "1.1" : "",
}

names = {
    "READ_ATOMIC_CONST_ORT": "RA-NOC2", 
    "READ_ATOMIC_FASTOPW": "RAMP-OPW",     
    "READ_ATOMIC_LIST": "RAMP-F",         
    "READ_ATOMIC_LORA": "LORA",          
    "READ_ATOMIC_STAMP": "RAMP-S",          
    "READ_ATOMIC_NOC": "RA-NOC",           
    "0.0" : "0",
    "0.3" : "0.3",
    "0.7" : "0.7",
    "0.8" : "0.8",
    "0.9" : "0.9",
    "0.99" : "0.99",
    "1.1" : "1.1",
    "1.2" : "1.2",
}

# data freshness x_axis
staleness = [10,30,50,100,500,3000]
staleness_string = []
for s in staleness:
    staleness_string.append(str(s))