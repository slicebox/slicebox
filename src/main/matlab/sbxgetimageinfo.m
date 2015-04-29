function datasets = sbxgetimageinfo(seriesid, sbxdata)
% Will fetch information about the dicom images of a series. The results
% are needed when using sbreadimage.
try
    url = [sbxdata.url, '/api/metadata/images?seriesgid=',num2str(seriesid)];
    datasets = webread(url, sbxdata.weboptions);
    seriesfile = fullfile(sbxdata.cachepath, ['seriesdata', num2str(seriesid)]);
    fprintf('saving %s', seriesfile);
    save(seriesfile,'datasets');
catch ME
    switch ME.identifier
        case 'MATLAB:webservices:CopyContentToDataStreamError'
            try 
                datasets = readinfofile(seriesid, sbxdata);
            catch
                ME2 = MException('SBXImageinfo:noSuchSeriesInCache','Could not retreive series with id %d.\nSeries not in cache and connection to server %s could not be established.', seriesid, sbxdata.url);
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