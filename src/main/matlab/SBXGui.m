function varargout = SBXGui(varargin)
% SBXGUI MATLAB code for SBXGui.fig
%      SBXGUI, by itself, creates a new SBXGUI or raises the existing
%      singleton*.
%
%      H = SBXGUI returns the handle to a new SBXGUI or the handle to
%      the existing singleton*.
%
%      SBXGUI('CALLBACK',hObject,eventData,handles,...) calls the local
%      function named CALLBACK in SBXGUI.M with the given input arguments.
%
%      SBXGUI('Property','Value',...) creates a new SBXGUI or raises the
%      existing singleton*.  Starting from the left, property value pairs are
%      applied to the GUI before SBXGui_OpeningFcn gets called.  An
%      unrecognized property name or invalid value makes property application
%      stop.  All inputs are passed to SBXGui_OpeningFcn via varargin.
%
%      *See GUI Options on GUIDE's Tools menu.  Choose "GUI allows only one
%      instance to run (singleton)".
%
% See also: GUIDE, GUIDATA, GUIHANDLES

% Edit the above text to modify the response to help SBXGui

% Last Modified by GUIDE v2.5 25-May-2015 14:17:09

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
% This function has no output args, see OutputFcn.
% hObject    handle to figure
% eventdata  reserved - to be defined in a future version of MATLAB
% handles    structure with handles and user data (see GUIDATA)
% varargin   command line arguments to SBXGui (see VARARGIN)

% Choose default command line output for SBXGui
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


% --- Outputs from this function are returned to the command line.
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


% --- Executes when selected cell(s) is changed in patientTable.
function patientTable_CellSelectionCallback(hObject, eventdata, handles)
% When a row in the patientTable is selected, populate the seriesTable with
% all the series which belong to that particular patient.
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
% imageinfo from slicebox. Also update infobox.
if(~isempty(eventdata.Indices))
    row = eventdata.Indices(1);
    seriesData = get(hObject,'Data');
    seriesId = seriesData{row,1};
    seriesinfo = sbxgetimageinfo(seriesId, handles.sbxdata);
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
% load the images in the series seleected in the seriesTable
if(isfield(handles, 'seriesinfo'))
    % add stuff from
    % http://undocumentedmatlab.com/blog/animated-busy-spinning-icon here
    [image, dcminfo] = sbxreadimages(handles.seriesinfo, handles.sbxdata);
    handles.image = image;
    handles.dcminfo = dcminfo;
    handles.closeReason = 0;
    guidata(handles.sbxgui, handles);
    close(handles.sbxgui);
else
    error('You must select a series to load!');
end

function loadImageToHandles(handles)



% --- Executes on button press in deleteButton.
function deleteButton_Callback(hObject, eventdata, handles)
% When clicked, remove all cached series data from local storage.


% --- Executes on button press in cancelButton.
function cancelButton_Callback(hObject, eventdata, handles)
% hObject    handle to cancelButton (see GCBO)
% eventdata  reserved - to be defined in a future version of MATLAB
% handles    structure with handles and user data (see GUIDATA)
handles.closeReason = 1;
guidata(handles.sbxgui, handles);
close(handles.sbxgui);

% --- Executes when user attempts to close sbxgui.
function sbxgui_CloseRequestFcn(hObject, eventdata, handles)
% hObject    handle to sbxgui (see GCBO)
% eventdata  reserved - to be defined in a future version of MATLAB
% handles    structure with handles and user data (see GUIDATA)

if isequal(get(hObject, 'waitstatus'), 'waiting')
    % The GUI is still in UIWAIT, us UIRESUME
    uiresume(hObject);
else
    % The GUI is no longer waiting, just close it
    delete(hObject);
end
