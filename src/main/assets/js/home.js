(function () {
   'use strict';
}());

angular.module('slicebox.home', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/', {
    templateUrl: '/assets/partials/home.html',
    controller: 'HomeCtrl'
  });
})

.controller('HomeCtrl', function($scope, $http, $modal, $q, openConfirmationDeleteModal) {
    // Initialization
    $scope.patientActions =
        [
            {
                name: 'Delete',
                action: confirmDeletePatients
            }
        ];

    $scope.studyActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteStudies
            }
        ];

    $scope.seriesActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteSeries
            }
        ];

    $scope.fileImageActions =
        [
            {
                name: 'Send',
                action: sendImageFiles
            }
        ];

    $scope.callbacks = {};

    $scope.uiState = {
        errorMessage: null,
        selectedPatient: null,
        selectedStudy: null,
        selectedSeries: null
    };

    // Scope functions
    $scope.loadPatients = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var loadPatientsUrl = '/api/metadata/patients?startindex=' + startIndex + '&count=' + count;
        if (orderByProperty) {
            var orderByPropertyName = orderByProperty.substring(0, orderByProperty.indexOf('['));
            loadPatientsUrl = loadPatientsUrl + '&orderby=' + orderByPropertyName;
            
            if (orderByDirection === 'ASCENDING') {
                loadPatientsUrl = loadPatientsUrl + '&orderascending=true';
            } else {
                loadPatientsUrl = loadPatientsUrl + '&orderascending=false';
            }
        }

        if (filter) {
            loadPatientsUrl = loadPatientsUrl + '&filter=' + encodeURIComponent(filter);
        }

        var loadPatientsPromise = $http.get(loadPatientsUrl);

        loadPatientsPromise.error(function(error) {
            appendErrorMessage('Failed to load patients: ' + error);
        });

        return loadPatientsPromise;
    };

    $scope.patientSelected = function(patient) {
        $scope.uiState.selectedPatient = patient;
        $scope.callbacks.studiesTable.reset();
    };

    $scope.loadStudies = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedPatient === null) {
            return [];
        }

        var loadStudiesPromise = $http.get('/api/metadata/studies?startindex=' + startIndex + '&count=' + count + '&patientId=' + $scope.uiState.selectedPatient.id);

        loadStudiesPromise.error(function(error) {
            appendErrorMessage('Failed to load studies: ' + error);
        });

        return loadStudiesPromise;
    };

    $scope.studySelected = function(study) {
        $scope.uiState.selectedStudy = study;
        $scope.callbacks.seriesTable.reset();
    };

    $scope.loadSeries = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedStudy === null) {
            return [];
        }

        var loadSeriesPromise = $http.get('/api/metadata/series?startindex=' + startIndex + '&count=' + count + '&studyId=' + $scope.uiState.selectedStudy.id);

        loadSeriesPromise.error(function(error) {
            appendErrorMessage('Failed to load series: ' + error);
        });

        return loadSeriesPromise;
    };

    $scope.seriesSelected = function(series) {
        $scope.uiState.selectedSeries = series;
        $scope.callbacks.imageFilesTable.reset();
    };

    $scope.loadImageFiles = function(startIndex, count, orderByProperty, orderByDirection) {
        if ($scope.uiState.selectedSeries === null) {
            return [];
        }

        var loadImageFilesPromise = $http.get('/api/metadata/imagefiles?seriesId=' + $scope.uiState.selectedSeries.id);

        loadImageFilesPromise.error(function(error) {
            appendErrorMessage('Failed to load image files: ' + error);
        });

        return loadImageFilesPromise;
    };

    $scope.closeErrorMessageAlert = function() {
        $scope.uiState.errorMessage = null;
    };

    // Private functions
    function appendErrorMessage(errorMessage) {
        if ($scope.uiState.errorMessage === null) {
            $scope.uiState.errorMessage = errorMessage;
        } else {
            $scope.uiState.errorMessage = $scope.uiState.errorMessage + '\n' + errorMessage;
        }
    }

    function confirmDeletePatients(patients) {
        var deleteConfirmationText = 'Permanently delete ' + patients.length + ' patients?';

        return openConfirmationDeleteModal('Delete Patients', deleteConfirmationText, function() {
            return deletePatients(patients);
        });
    }

    function deletePatients(patients) {
        var deletePromises = [];
        var deletePromise;

        angular.forEach(patients, function(patient) {
            deletePromise = $http.delete('/api/metadata/patients/' + patient.id);
            deletePromises.push(deletePromise);

            deletePromise.error(function(error) {
                appendErrorMessage('Failed to delete patient: ' + error);
            });
        });

        return $q.all(deletePromises);
    }

    function confirmDeleteStudies(studies) {
        var deleteConfirmationText = 'Permanently delete ' + studies.length + ' studies?';

        return openConfirmationDeleteModal('Delete Studies', deleteConfirmationText, function() {
            return deleteStudies(studies);
        });
    }

    function deleteStudies(studies) {
        var deletePromises = [];
        var deletePromise;

        angular.forEach(studies, function(study) {
            deletePromise = $http.delete('/api/metadata/studies/' + study.id);
            deletePromises.push(deletePromise);

            deletePromise.error(function(error) {
                appendErrorMessage('Failed to delete study: ' + error);
            });
        });

        return $q.all(deletePromises);
    }

    function confirmDeleteSeries(series) {
        var deleteConfirmationText = 'Permanently delete ' + series.length + ' series?';

        return openConfirmationDeleteModal('Delete Series', deleteConfirmationText, function() {
            return deleteSeries(series);
        });
    }

    function deleteSeries(series) {
        var deletePromises = [];
        var deletePromise;

        angular.forEach(series, function(theSeries) {
            deletePromise = $http.delete('/api/metadata/series/' + theSeries.id);
            deletePromises.push(deletePromise);

            deletePromise.error(function(error) {
                appendErrorMessage('Failed to delete series: ' + error);
            });
        });

        return $q.all(deletePromises);
    }

    function sendImageFiles(imageFiles) {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/sendImageFilesModalContent.html',
                controller: 'SendImageFilesModalCtrl',
                resolve: {
                    imageFiles: function () {
                        return imageFiles;
                    }
                }
            });
    }
})

.controller('SendImageFilesModalCtrl', function($scope, $modalInstance, $http, $q, imageFiles) {
    // Initialization
    $scope.uiState = {
        errorMessages: [],
        selectedReceiver: null
    };

    $scope.imageFiles = imageFiles;

    // Scope functions
    $scope.loadBoxesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/boxes');
    };

    $scope.boxSelected = function(box) {
        $scope.uiState.selectedReceiver = box;
    };

    $scope.closeErrorMessageAlert = function(errorIndex) {
        $scope.uiState.errorMessages.splice(errorIndex, 1);
    };

    $scope.sendButtonClicked = function() {
        var imageIds = [];
        var sendPromise;

        $scope.uiState.sendInProgress = true;

        angular.forEach($scope.imageFiles, function(imageFile) {
            imageIds.push(imageFile.id);
        });

        sendPromise = $http.post('/api/boxes/' + $scope.uiState.selectedReceiver.id + '/sendimages', imageIds);

        sendPromise.error(function(data) {
            $scope.uiState.errorMessages.push(data);
        });

        sendPromise.then(function() {
            $modalInstance.close();
        });

        sendPromise.finally(function() {
            $scope.uiState.sendInProgress = false;
        });
    };

    $scope.cancelButtonClicked = function() {
        $modalInstance.dismiss();
    };
});