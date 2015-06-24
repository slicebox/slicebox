function varargout = SBXGui(varargin)
% SBXGUI Provides a simple gui to select and download dicom images from a
% slicebox server.
%      [I, dcminfo] = SBXGUI(username, password) Launches the gui, which
%      provides a simple way to select and download dicom files from a
%      slicebox server. If no sbxsettings.conf exists, the options'url', and
%      'cachepath' must also be specified.


% Begin initialization code - DO NOT EDIT
gui_Singleton = 1;
gui_State = struct('gui_Name',       mfilename, ...
                   'gui_Singleton',  gui_Singleton, ...
                   'gui_OpeningFcn', @SBXGui_OpeningFcn, ...
                   'gui_OutputFcn',  @SBXGui_OutputFcn, ...
                   'gui_LayoutFcn',  [] , ...
                   'gui_Callback',   []);
if nargin && ischar(varargin{1})
    gui_State.gui_Callback = str2func(varargin{1});
end

if nargout
    [varargout{1:nargout}] = gui_mainfcn(gui_State, varargin{:});
else
    gui_mainfcn(gui_State, varargin{:});
end
% End initialization code - DO NOT EDIT


% --- Executes just before SBXGui is made visible.
function SBXGui_OpeningFcn(hObject, eventdata, handles, varargin)
% Create the sbxdata object and fetch the flattened database image.
sbxdata = makesbxdata(varargin{:});
flatseries = sbxgetflatseries(sbxdata);
patients = [];
idlist = [];
for i = 2:length(flatseries)
   if ~any(idlist==flatseries(i).patient.id)
       patient = flatseries(i).patient;
       patient.numberOfSeries = 1;
       patients = [patients, patient];
       idlist = [idlist, flatseries(i).patient.id];
   else
       j = find(idlist == flatseries(i).patient.id);
       patients(j).numberOfSeries = patients(j).numberOfSeries + 1;
   end
end
handles.sbxdata = sbxdata;
handles.patients = patients;
handles.flatseries = flatseries;

patientTable = handles.patientTable;
data = cell(length(patients), 4);
for i = 1:length(patients);
    data(i,:) = {patients(i).id, patients(i).patientName.value,...
        patients(i).patientID.value, patients(i).numberOfSeries};
end
set(patientTable, 'Data', data);
% Update handles structure
guidata(hObject, handles);

% UIWAIT makes SBXGui wait for user response (see UIRESUME)
uiwait(handles.sbxgui);

% If a series has been selected, return the image and dicom info.
% Otherwise, return empty cells.
function varargout = SBXGui_OutputFcn(hObject, eventdata, handles)
if(handles.closeReason == 0)
    varargout{1} = handles.image;
    varargout{2} = handles.dcminfo;
else
    varargout{1} = {};
    varargout{2} = {};
end
% The figure can be deleted now
delete(handles.sbxgui);


function patientTable_CellSelectionCallback(hObject, eventdata, handles)
% When a row in the patientTable is selected, populate the seriesTable with
% all the series which belong to that particular patient.
if handles.isBusy
    return;
end
row = eventdata.Indices(1);
patientData = get(hObject, 'Data');
id = patientData{row,1};
seriesTable = handles.seriesTable;
series = [];
for i = 1:length(handles.flatseries)
    if handles.flatseries(i).patient.id == id
        series = [series handles.flatseries(i).series];
    end
end
data = cell(length(series),5);
for i = 1:length(series)
    data(i,:) = {series(i).id, series(i).studyId, series(i).seriesDate.value,...
        series(i).seriesDescription.value, series(i).modality.value};
end
set(seriesTable, 'Data', data);
guidata(hObject,handles);

% --- Executes when selected cell(s) is changed in seriesTable.
function seriesTable_CellSelectionCallback(hObject, eventdata, handles)
% When clicking a cell (well, row really) in seriesTable, pull the
% imageinfo from slicebox. Also update the infobox. While working, disable
% all the buttons.
if(~isempty(eventdata.Indices))
    row = eventdata.Indices(1);
    seriesData = get(hObject,'Data');
    seriesId = seriesData{row,1};
    isBusy(true);
    seriesinfo = sbxgetimageinfo(seriesId, handles.sbxdata);
    isBusy(false);
    handles.seriesinfo = seriesinfo;
    
    infotext = handles.infotext;
    textdata = sprintf('Number of Slices: %d\nType: %s',length(seriesinfo),...
        seriesinfo(1).imageType.value);
    set(infotext, 'String', textdata);
else
    infotext = handles.infotext;
    textdata = [];
    set(infotext, 'String', textdata);
end
guidata(hObject,handles);

% --- Executes on button press in loadButton.
function loadButton_Callback(hObject, eventdata, handles)
% load the images in the series seleected in the seriesTable. Disable all
% buttons while files are being downloaded.
if(isfield(handles, 'seriesinfo'))
    % add stuff from
    % http://undocumentedmatlab.com/blog/animated-busy-spinning-icon here
    setBusy(true);
    [image, dcminfo] = sbxreadimages(handles.seriesinfo, handles.sbxdata);
    setBusy(false);
    handles.image = image;
    handles.dcminfo = dcminfo;
    handles.closeReason = 0;
    guidata(handles.sbxgui, handles);
    close(handles.sbxgui);
else
    error('You must select a series to load!');
end

function deleteButton_Callback(hObject, eventdata, handles)
% When clicked, remove all cached series data from local storage.

function cancelButton_Callback(hObject, eventdata, handles)
handles.closeReason = 1;
guidata(handles.sbxgui, handles);
close(handles.sbxgui);

function sbxgui_CloseRequestFcn(hObject, eventdata, handles)

if isequal(get(hObject, 'waitstatus'), 'waiting')
    % The GUI is still in UIWAIT, us UIRESUME
    uiresume(hObject);
else
    % The GUI is no longer waiting, just close it
    delete(hObject);
end

function setBusy(busy, handles)
if busy
    handles.isBusy = true;
    set(handles.figure1, 'pointer', 'clock');
    set(handles.loadButton, 'Enable', 'off');
    set(handles.reloadButton, 'Enable', 'off');
    set(handles.deleteButton, 'Enable', 'off');
    set(handles.cancelButton, 'Enable', 'off');
    guidata(handles.sbxgui, handles);
else
    handles.isBusy = false;
    set(handles.figure1, 'pointer', 'arrow');
    set(handles.loadButton, 'Enable', 'on');
    set(handles.reloadButton, 'Enable', 'on');
    set(handles.deleteButton, 'Enable', 'on');
    set(handles.cancelButton, 'Enable', 'on');
    guidata(handles.sbxgui, handles);
end
    
