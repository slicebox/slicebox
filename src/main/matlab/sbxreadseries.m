function images = sbxreadseries(seriesid, sbdata, varargin)

%add functionality for selecing a range of images

datasets = sbxgetimageinfo(seriesid, sbdata);
images = cell(length(datasets),1);
for i = 1:length(datasets)
    z = str2double(datasets(i).instanceNumber.value);
    images{z} = sbimageread(datasets(i),sbdata);
end