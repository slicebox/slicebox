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

.controller('HomeCtrl', function($scope, $http) {
    // Initialization
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
});