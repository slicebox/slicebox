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

.controller('HomeCtrl', function($scope, $http, $modal) {
    // Initialization
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
    $scope.loadPatients = function(startIndex, count, orderByProperty, orderByDirection) {
        var loadPatientsPromise = $http.get('/api/metadata/patients');

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

        var loadStudiesPromise = $http.get('/api/metadata/studies?patientId=' + $scope.uiState.selectedPatient.id);

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

        var loadSeriesPromise = $http.get('/api/metadata/series?studyId=' + $scope.uiState.selectedStudy.id);

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
        var sendPromises = [];
        var sendPromise;
        var sendAllPromise;

        $scope.uiState.sendInProgress = true;

        angular.forEach($scope.imageFiles, function(imageFile) {
            sendPromise = $http.post('/api/boxes/' + $scope.uiState.selectedReceiver.id + '/sendimage',
                {
                    value: imageFile.id
                });

            sendPromise.error(function(data) {
                $scope.uiState.errorMessages.push(data);
            });

            sendPromises.push(sendPromise);
        });

        sendAllPromise = $q.all(sendPromises);

        sendAllPromise.then(function() {
            $modalInstance.close();
        });

        sendAllPromise.finally(function() {
            $scope.uiState.sendInProgress = false;
        });
    };

    $scope.cancelButtonClicked = function() {
        $modalInstance.dismiss();
    };
});