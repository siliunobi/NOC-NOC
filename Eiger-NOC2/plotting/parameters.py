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
allBar = True
freshBar = True
plus = True
normalize = True
normalizer = EIGER_PORT
tp_label = "Throughput (ops/s)"
lat_label = "Latency (ms)"

zipfs = ["0.0", "0.3", "0.7", "0.8", "0.9", "0.99", "1.1", "1.2"]

algorithms = ["EIGER", "EIGER_PORT", "EIGER_PORT_PLUS_PLUS"]
saveTo = "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/plotting/plots/noplus/"
# edit this if you want to change the algorithms you can plot
if plus:
    algorithms = ["EIGER", "EIGER_PORT", "EIGER_PORT_PLUS", "EIGER_PORT_PLUS_PLUS"]
    saveTo = "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/plotting/plots/plus/"

if normalize:
    algorithms = [normalizer, "EIGER_PORT_PLUS_PLUS"]
    if normalizer == EIGER_PORT:
        saveTo = "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/plotting/plots/normalized/"
        if plus:
            saveTo = "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/plotting/plots/normalizedPlusVsPort/"
            algorithms = [normalizer, "EIGER_PORT_PLUS"]
    else:
        algorithms = ["EIGER_PORT", normalizer, "EIGER_PORT_PLUS_PLUS"]
        saveTo = "/home/luca/ETH/Thesis/EIGERPORT++/Eiger-PORT-plus-plus/plotting/plots/normalizedVsPlus/"
    tp_label = "Normalized Throughput"
    lat_label = "Normalized Latency"
#algorithms = ["EIGER","EIGER_PORT", "EIGER_PORT_PLUS_PLUS"]
# save the images as pdfs here

if not allBar:
    saveTo = saveTo + "noBar/"
 
colors = {
    "EIGER": "#1f77b4",
    "EIGER_PORT": "#ff7f0e",
    "EIGER_PORT_PLUS": "#2ca02c",
    "EIGER_PORT_PLUS_PLUS": "#9467bd",
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
    "EIGER": "o",
    "EIGER_PORT": "s",
    "EIGER_PORT_PLUS": "v",
    "EIGER_PORT_PLUS_PLUS": "x",
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
    "EIGER": "Eiger",
    "EIGER_PORT": "Eiger-PORT",
    "EIGER_PORT_PLUS": "Eiger-PORT+",
    "EIGER_PORT_PLUS_PLUS": "Eiger-PORT++",
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