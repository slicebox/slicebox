function datasets = sbxgetimageinfo(seriesid, sbxdata)
% Will fetch information about the dicom images of a series. The results
% are needed when using sbreadimage.

% If the connection to the slicebox service fails for some reson, the image
% info will be read from a local copy, if one exists.

try
    url = [sbxdata.url, '/api/metadata/images?count=1000000&seriesid=',num2str(seriesid)];
    unsorteddatasets = webread(url, sbxdata.weboptions);
    datasets = cell(size(unsorteddatasets));
    for i = 1:numel(unsorteddatasets)
        z = str2double(imagedata(i).instanceNumber.value);
        datasets{z} = unsorteddatasets{i};
    end
    seriesfile = fullfile(sbxdata.cachepath, ['seriesdata', num2str(seriesid)]);
    %fprintf('saving %s\n', seriesfile);
    save(seriesfile,'datasets');
catch ME
    switch ME.identifier
        case 'MATLAB:webservices:CopyContentToDataStreamError'
            try 
                datasets = readinfofile(seriesid, sbxdata);
            catch
                ME2 = MException('SBX:imageinfo:NoSuchSeriesInCache','Could not retreive series with id %d.\nSeries not in cache and connection to server %s could not be established.',...
                    seriesid, sbxdata.url);
                throw(ME2);
            end
        case 'MATLAB:webservices:HTTP401StatusCodeError'
            error('Authorization failed; Incorrect username or password.');
        otherwise
            rethrow(ME);
    end
end
end

function datasets = readinfofile(seriesid, sbxdata) %#ok<STOUT>
    seriesfile = fullfile(sbxdata.cachepath, ['seriesdata', num2str(seriesid)]);
    load(seriesfile,'datasets');
end